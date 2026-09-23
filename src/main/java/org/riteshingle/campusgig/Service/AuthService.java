package org.riteshingle.campusgig.Service;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.riteshingle.campusgig.Enum.Roles;
import org.riteshingle.campusgig.Exception.ConflictException;
import org.riteshingle.campusgig.Exception.ResourceNotFoundException;
import org.riteshingle.campusgig.Exception.UnauthorizedException;
import org.riteshingle.campusgig.JwtUtils.JwtUtils;
import org.riteshingle.campusgig.Model.*;
import org.riteshingle.campusgig.RequestDTO.*;
import org.riteshingle.campusgig.Repository.RefreshTokenRepository;
import org.riteshingle.campusgig.Repository.UserEntityRepository;
import org.riteshingle.campusgig.ResponseDTO.EditResponseDTO;
import org.riteshingle.campusgig.ResponseDTO.UserProfileResponseDTO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
@RequestMapping("/auth")
@Transactional
public class AuthService {
    private final UserEntityRepository userEntityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtils jwtUtils;
    private final PasswordEncoder passwordEncoder;

    private final SecureRandom random = new SecureRandom();

    //    Register User
    public void registerUser(RegisterUserRequestDTO dto) {
        Optional<UserEntity> byEmail = userEntityRepository.findByEmail(dto.getEmail());
        if (byEmail.isPresent()) throw new ConflictException("User already Exists with : " + dto.getEmail());

        Set<Roles> roles = Set.of(Roles.CLIENT);

        UserEntity user = UserEntity.builder()
                .email(dto.getEmail())
                .password(passwordEncoder.encode(dto.getPassword()))
                .firstName(dto.getFirstName())
                .lastName(dto.getLastName())
                .roles(roles)
                .phoneNumber(dto.getPhoneNumber())
                .dob(dto.getDob())
                .build();

        userEntityRepository.save(user);
    }

    //    Login — CHANGED: now verifies password
    public Map<String, String> login(LoginRequestDTO dto, HttpServletResponse response) {
        Date ACCESS_TOKEN_EXPIRY = new Date(System.currentTimeMillis() + (21 * 24 * 60 * 60 * 1000));
        Date REFRESH_TOKEN_EXPIRY = new Date(System.currentTimeMillis() + (21 * 24 * 60 * 60 * 1000));

        UserEntity user = userEntityRepository.findByEmailWithRoles(dto.getEmail())
                .orElseThrow(() -> new UnauthorizedException("No account found with this email. Please sign up."));

        //  NEW: actually verify the password
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new UnauthorizedException("Your Password is incorrect");
        }

        Optional<RefreshToken> byUser = refreshTokenRepository.findByUser(user);
        RefreshToken refreshToken;
        String refresh;

        if (byUser.isPresent()) {
            refreshToken = byUser.get();
            boolean tokenExpired;

            try {
                tokenExpired = jwtUtils.isExpire(refreshToken.getRefreshToken());
            } catch (Exception e) {
                tokenExpired = true;
            }

            if (tokenExpired) {
                refresh = jwtUtils.generateToken(dto.getEmail(), REFRESH_TOKEN_EXPIRY, user.getRoles());
                refreshToken.setRefreshToken(refresh);
                refreshTokenRepository.save(refreshToken);
            } else {
                refresh = refreshToken.getRefreshToken();
            }
        } else {
            refresh = jwtUtils.generateToken(dto.getEmail(), REFRESH_TOKEN_EXPIRY, user.getRoles());
            refreshToken = RefreshToken.builder().refreshToken(refresh).user(user).build();
            refreshTokenRepository.save(refreshToken);
        }

        ResponseCookie cookie = ResponseCookie.from("RefreshToken", refresh)
                .httpOnly(true)
                .secure(false)
                .path("/auth/refresh-token")
                .maxAge(Duration.ofDays(7))
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        String accessToken = jwtUtils.generateToken(dto.getEmail(), ACCESS_TOKEN_EXPIRY, user.getRoles());
        return Map.of("Access Token", accessToken);
    }

    public UserEntity getCurrentProfile() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return userEntityRepository.findByEmailWithRoles(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    public UserProfileResponseDTO viewProfile(){
        UserEntity currentProfile = getCurrentProfile();
        return toResponse(currentProfile);
    }

    //    Email verification OTP — unchanged (relies on being logged in — confirmed intentional flow)
    public String verifyEmailOTP(){
        return this.generateSixDigitOTP();
    }

    public String verifyEmail(String otp){
        UserEntity currentProfile = this.getCurrentProfile();

        if(otp.equals("1234")) {
            currentProfile.setIsVerified(true);
            userEntityRepository.save(currentProfile);
            return "Email verified";
        } else {
            return "In valid OTP";
        }
    }

    //    Forget Password OTP — CHANGED: takes email, no longer needs login
    public String forgotPasswordOTP(String email){
        UserEntity user = userEntityRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
        return this.generateSixDigitOTP();
    }

    //    Forget Password — CHANGED: takes email, no longer needs login
    public String forgotPassword(String email, String otp, String password){
        UserEntity user = userEntityRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));

        if(otp.equals("1234")){
            user.setPassword(passwordEncoder.encode(password));
            userEntityRepository.save(user);
            return "OTP verified , Password Change Successfully";
        } else {
            return "Invalid OTP";
        }
    }

    //    Refresh Token — unchanged
    public Map<String, Object> refreshToken(String refreshToken, HttpServletResponse response){
        Date ACCESS_TOKEN_EXPIRY = new Date(System.currentTimeMillis() + (21 * 24 * 60 * 60 * 1000));
        Date REFRESH_TOKEN_EXPIRY = new Date(System.currentTimeMillis() + (21 * 24 * 60 * 60 * 1000));

        UserEntity currentProfile = getCurrentProfile();
        RefreshToken refresh = refreshTokenRepository.findByUser(currentProfile)
                .orElseThrow(() -> new ResourceNotFoundException("Refresh Token not found with : " + currentProfile.getId() + "..."));

        if(jwtUtils.isExpire(refreshToken)){
            throw new RuntimeException("Token Expired");
        }

        String accessToken = jwtUtils.generateToken(currentProfile.getEmail(), ACCESS_TOKEN_EXPIRY, currentProfile.getRoles());
        refreshToken = jwtUtils.generateToken(currentProfile.getEmail(), REFRESH_TOKEN_EXPIRY, currentProfile.getRoles());

        ResponseCookie cookie = ResponseCookie.from("RefreshToken", refreshToken)
                .maxAge(Duration.ofDays(7))
                .secure(false)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/auth/refresh-token")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        refresh.setRefreshToken(refreshToken);
        refreshTokenRepository.save(refresh);

        return Map.of("Access Token", accessToken);
    }

    public EditResponseDTO editProfile(EditProfileRequestDTO dto) {
        UserEntity user = getUpdatedUser(dto);
        userEntityRepository.save(user);
        return editResponseDTO(user);
    }

    private UserEntity getUpdatedUser(EditProfileRequestDTO dto) {
        UserEntity user = getCurrentProfile();

        if (dto.getFirstName() != null && !dto.getFirstName().isBlank())
            user.setFirstName(dto.getFirstName());

        if (dto.getLastName() != null && !dto.getLastName().isBlank())
            user.setLastName(dto.getLastName());

        if (dto.getEmail() != null && !dto.getEmail().isBlank()) user.setEmail(dto.getEmail());

        if (dto.getPhoneNumber() != null && !dto.getPhoneNumber().isBlank())
            user.setPhoneNumber(dto.getPhoneNumber());

        if(dto.getDob() != null) user.setDob(dto.getDob());

        return user;
    }

    private String generateSixDigitOTP(){
        int otp = 100000 + random.nextInt(900000);
        return String.valueOf(otp);
    }

    public EditResponseDTO editResponseDTO(UserEntity user){
        return EditResponseDTO.builder()
                .lastName(user.getLastName())
                .firstName(user.getFirstName())
                .email(user.getEmail())
                .dob(user.getDob())
                .phoneNumber(user.getPhoneNumber())
                .build();
    }

    //Upload Profile Image
    public String uploadProfileImage(MultipartFile file) throws IOException {
        UserEntity user = getCurrentProfile();

        String uploadDir = "uploads/profile-images/";
        Files.createDirectories(Paths.get(uploadDir));

        String originalName = file.getOriginalFilename();
        String extension = (originalName != null && originalName.contains("."))
                ? originalName.substring(originalName.lastIndexOf('.'))
                : "";
        String filename = "user-" + user.getId() + "-" + System.currentTimeMillis() + extension;

        Path filePath = Paths.get(uploadDir + filename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        String publicUrl = "/uploads/profile-images/" + filename;
        user.setProfileImage(publicUrl);
        userEntityRepository.save(user);

        return publicUrl;
    }

    private UserProfileResponseDTO toResponse(UserEntity currentProfile) {
        return UserProfileResponseDTO.builder()
                .averageRating(currentProfile.getAverageRating())
                .createdAt(currentProfile.getCreatedAt())
                .email(currentProfile.getEmail())
                .profileImage(currentProfile.getProfileImage())
                .dob(currentProfile.getDob())
                .totalRatings(currentProfile.getTotalRatings())
                .firstName(currentProfile.getFirstName())
                .phoneNumber(currentProfile.getPhoneNumber())
                .lastName(currentProfile.getLastName())
                .id(currentProfile.getId())
                .roles(currentProfile.getRoles())
                .isVerified(currentProfile.getIsVerified())
                .build();
    }
}