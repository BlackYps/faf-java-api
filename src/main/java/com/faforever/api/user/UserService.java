package com.faforever.api.user;

import com.faforever.api.config.FafApiProperties;
import com.faforever.api.data.domain.NameRecord;
import com.faforever.api.data.domain.User;
import com.faforever.api.email.EmailService;
import com.faforever.api.error.ApiException;
import com.faforever.api.error.Error;
import com.faforever.api.error.ErrorCode;
import com.faforever.api.player.PlayerRepository;
import com.faforever.api.security.FafPasswordEncoder;
import com.faforever.api.security.FafUserDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.jwt.JwtHelper;
import org.springframework.security.jwt.crypto.sign.MacSigner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@Slf4j
public class UserService {
  static final String KEY_ACTION = "action";
  static final String KEY_EXPIRY = "expiry";
  static final String KEY_USERNAME = "username";
  static final String KEY_EMAIL = "email";
  static final String KEY_PASSWORD = "password";
  static final String KEY_USER_ID = "id";
  private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,15}$");
  private static final String ACTION_ACTIVATE = "activate";
  private static final String ACTION_RESET_PASSWORD = "reset_password";
  private final EmailService emailService;
  private final PlayerRepository playerRepository;
  private final UserRepository userRepository;
  private final NameRecordRepository nameRecordRepository;
  private final ObjectMapper objectMapper;
  private final MacSigner macSigner;
  private final FafApiProperties properties;
  private final FafPasswordEncoder passwordEncoder;

  public UserService(EmailService emailService, PlayerRepository playerRepository, UserRepository userRepository,
                     NameRecordRepository nameRecordRepository, ObjectMapper objectMapper, FafApiProperties properties) {
    this.emailService = emailService;
    this.playerRepository = playerRepository;
    this.userRepository = userRepository;
    this.nameRecordRepository = nameRecordRepository;
    this.objectMapper = objectMapper;
    this.macSigner = new MacSigner(properties.getJwt().getSecret());
    this.properties = properties;
    this.passwordEncoder = new FafPasswordEncoder();
  }

  void register(String username, String email, String password) {
    log.debug("Registration requested for user: {}", username);
    validateUsername(username);
    emailService.validateEmailAddress(email);

    if (userRepository.existsByEmailIgnoreCase(email)) {
      throw new ApiException(new Error(ErrorCode.EMAIL_REGISTERED, email));
    }

    String token = createRegistrationToken(username, email, passwordEncoder.encode(password));
    String activationUrl = String.format(properties.getRegistration().getActivationUrlFormat(), token);

    emailService.sendActivationMail(username, email, activationUrl);
  }

  private void validateUsername(String username) {
    if (!USERNAME_PATTERN.matcher(username).matches()) {
      throw new ApiException(new Error(ErrorCode.USERNAME_INVALID, username));
    }
    if (userRepository.existsByLoginIgnoreCase(username)) {
      throw new ApiException(new Error(ErrorCode.USERNAME_TAKEN, username));
    }
  }

  @SneakyThrows
  private String createRegistrationToken(String username, String email, String passwordHash) {
    long expirationSeconds = properties.getRegistration().getLinkExpirationSeconds();

    String claim = objectMapper.writeValueAsString(ImmutableMap.of(
      KEY_ACTION, ACTION_ACTIVATE,
      KEY_EXPIRY, Instant.now().plusSeconds(expirationSeconds).toString(),
      KEY_USERNAME, username,
      KEY_EMAIL, email,
      KEY_PASSWORD, passwordHash
    ));

    return JwtHelper.encode(claim, macSigner).getEncoded();
  }

  /**
   * Creates a new user based on the information in the activation token.
   *
   * @param token the JWT in the format: <pre>
   *   {
   *     "action": "activate",
   *     "expiry": "2011-12-03T10:15:30Z",
   *     "
   *   }
   * </pre>
   */
  @SneakyThrows
  @SuppressWarnings("unchecked")
  @Transactional
  void activate(String token) {
    HashMap<String, String> claims = objectMapper.readValue(JwtHelper.decodeAndVerify(token, macSigner).getClaims(), HashMap.class);

    String action = claims.get(KEY_ACTION);
    if (!Objects.equals(action, ACTION_ACTIVATE)) {
      throw new ApiException(new Error(ErrorCode.TOKEN_INVALID));
    }
    if (Instant.parse(claims.get(KEY_EXPIRY)).isBefore(Instant.now())) {
      throw new ApiException(new Error(ErrorCode.TOKEN_EXPIRED));
    }

    String username = claims.get(KEY_USERNAME);
    String email = claims.get(KEY_EMAIL);
    String password = claims.get(KEY_PASSWORD);

    User user = new User();
    user.setPassword(password);
    user.setEmail(email);
    user.setLogin(username);

    log.debug("User has been activated: {}", user);
    userRepository.save(user);
  }

  void changePassword(String currentPassword, String newPassword, User user) {
    if (!Objects.equals(user.getPassword(), passwordEncoder.encode(currentPassword))) {
      throw new ApiException(new Error(ErrorCode.PASSWORD_CHANGE_FAILED));
    }

    user.setPassword(passwordEncoder.encode(newPassword));
    userRepository.save(user);
  }

  void changeLogin(String newLogin, User user) {
    validateUsername(newLogin);

    int minDaysBetweenChange = properties.getUser().getMinimumDaysBetweenUsernameChange();
    nameRecordRepository.getDaysSinceLastNewRecord(user.getId(), minDaysBetweenChange)
      .ifPresent(daysSinceLastRecord -> {
        throw new ApiException(new Error(ErrorCode.USERNAME_CHANGE_TOO_EARLY, minDaysBetweenChange - daysSinceLastRecord));
      });

    NameRecord nameRecord = new NameRecord()
      .setName(user.getLogin())
      .setPlayer(playerRepository.findOne(user.getId()));
    nameRecordRepository.save(nameRecord);

    user.setLogin(newLogin);
    userRepository.save(user);
  }

  void resetPassword(String email) {
    log.debug("Registration requested for user: {}", email);

    User user = userRepository.findOneByEmailIgnoreCase(email);

    if (user == null) {
      throw new ApiException(new Error(ErrorCode.USERNAME_INVALID));
    }

    String token = createPasswordResetToken(user.getId());
    String passwordResetUrl = String.format(properties.getPasswordReset().getPasswordResetUrlFormat(), token);

    emailService.sendPasswordResetMail(user.getLogin(), user.getEmail(), passwordResetUrl);
  }

  @SneakyThrows
  private String createPasswordResetToken(int userId) {
    long expirationSeconds = properties.getRegistration().getLinkExpirationSeconds();

    String claim = objectMapper.writeValueAsString(ImmutableMap.of(
      KEY_ACTION, ACTION_RESET_PASSWORD,
      KEY_EXPIRY, Instant.now().plusSeconds(expirationSeconds).toString(),
      KEY_USER_ID, userId
    ));

    return JwtHelper.encode(claim, macSigner).getEncoded();
  }

  @SneakyThrows
  void claimPasswordResetToken(String token, String newPassword) {
    HashMap<String, String> claims = objectMapper.readValue(JwtHelper.decodeAndVerify(token, macSigner).getClaims(), HashMap.class);

    String action = claims.get(KEY_ACTION);
    if (!Objects.equals(action, ACTION_RESET_PASSWORD)) {
      throw new ApiException(new Error(ErrorCode.TOKEN_INVALID));
    }
    if (Instant.parse(claims.get(KEY_EXPIRY)).isBefore(Instant.now())) {
      throw new ApiException(new Error(ErrorCode.TOKEN_EXPIRED));
    }

    int userId = Integer.parseInt(claims.get(KEY_USER_ID));
    User user = userRepository.findOne(userId);

    if (user == null) {
      throw new ApiException(new Error(ErrorCode.TOKEN_INVALID));
    }

    user.setPassword(passwordEncoder.encode(newPassword));
    userRepository.save(user);
  }

  public User getUser(Authentication authentication) {
    if (authentication != null
      && authentication.getPrincipal() != null
      && authentication.getPrincipal() instanceof FafUserDetails) {
      return userRepository.findOne(((FafUserDetails) authentication.getPrincipal()).getId());
    }

    throw new IllegalStateException("Authentication missing");
  }
}
