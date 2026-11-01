/*
 * Copyright 2026 Philterd, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.philterd.workspace.webapp.controllers;

import ai.philterd.workspace.webapp.security.PendingPhilterSignIn;
import ai.philterd.workspace.webapp.security.PendingPhilterSignIn.Step;
import ai.philterd.workspace.webapp.security.PhilterSessionAuthentication;
import ai.philterd.workspace.webapp.security.PhilterSignInFlow;
import ai.philterd.workspace.webapp.security.PhilterSignInService;
import ai.philterd.workspace.webapp.security.PhilterSignInStepException;
import ai.philterd.workspace.webapp.security.MfaQrCodes;
import ai.philterd.philter.model.MfaEnrollment;
import ai.philterd.philter.model.exceptions.ClientException;
import ai.philterd.philter.model.exceptions.SignInThrottledException;
import ai.philterd.philter.model.exceptions.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The sign-in steps that follow the password when people sign in through Philter: an MFA code, a forced
 * password change, and forced MFA enrollment. The person is not signed in to Philter Workspace until every step
 * is done, so no other page can be reached before them. Changing the password or confirming enrollment
 * makes Philter revoke the person's session keys, so both end by sending the person back to sign in.
 */
@Controller
@RequestMapping("/sign-in")
public class PhilterSignInController {

    private final PhilterSignInService signInService;
    private final PhilterSignInFlow flow;
    private final MfaQrCodes qrCodes;
    private final SecurityContextRepository securityContextRepository;
    private final SessionRegistry sessionRegistry;
    private final CsrfTokenRepository csrfTokenRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public PhilterSignInController(final PhilterSignInService signInService,
                                   final PhilterSignInFlow flow,
                                   final MfaQrCodes qrCodes,
                                   final SecurityContextRepository securityContextRepository,
                                   final SessionRegistry sessionRegistry,
                                   final CsrfTokenRepository csrfTokenRepository) {
        this.sessionRegistry = sessionRegistry;
        this.csrfTokenRepository = csrfTokenRepository;
        this.signInService = signInService;
        this.flow = flow;
        this.qrCodes = qrCodes;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping("/mfa")
    public String mfa(final HttpSession session) {
        return PendingPhilterSignIn.from(session, Step.MFA) == null ? "redirect:/login" : "sign-in-mfa";
    }

    @PostMapping("/mfa")
    public String completeMfa(@RequestParam("code") final String code,
                              final HttpServletRequest request,
                              final HttpServletResponse response) {
        final HttpSession session = request.getSession(false);
        final PendingPhilterSignIn pending = PendingPhilterSignIn.from(session, Step.MFA);
        if (pending == null) {
            return "redirect:/login";
        }
        // Any attempt uses up Philter's challenge, so the pending step goes either way.
        PendingPhilterSignIn.clear(session);
        final PhilterSessionAuthentication authentication;
        try {
            authentication = flow.complete(signInService.completeSignIn(pending.encryptedSecret(), code.trim(),
                    PhilterSignInFlow.clientAddress(request), PhilterSignInFlow.userAgent(request)),
                    pending.username());
        } catch (PhilterSignInStepException e) {
            request.getSession(true).setAttribute(PendingPhilterSignIn.SESSION_ATTRIBUTE, e.getPending());
            return "redirect:" + e.getPending().step().path();
        } catch (AuthenticationException e) {
            return "redirect:" + loginFor(e, "code");
        } catch (Exception e) {
            return "redirect:" + loginFor(flow.refused(pending.username(), e), "code");
        }
        try {
            signIn(authentication, request, response);
        } catch (RuntimeException e) {
            flow.signOutQuietly(authentication.getEncryptedSessionKey());
            contextHolder.clearContext();
            return "redirect:/login?unavailable";
        }
        return "redirect:/";
    }

    @GetMapping("/password")
    public String password(final HttpSession session, final Model model) {
        final PendingPhilterSignIn pending = PendingPhilterSignIn.from(session, Step.PASSWORD_CHANGE);
        if (pending == null) {
            return "redirect:/login";
        }
        model.addAttribute("username", pending.username());
        return "sign-in-password";
    }

    @PostMapping("/password")
    public String changePassword(@RequestParam("currentPassword") final String currentPassword,
                                 @RequestParam("newPassword") final String newPassword,
                                 @RequestParam("confirmPassword") final String confirmPassword,
                                 final HttpSession session,
                                 final Model model) {
        final PendingPhilterSignIn pending = PendingPhilterSignIn.from(session, Step.PASSWORD_CHANGE);
        if (pending == null) {
            return "redirect:/login";
        }
        model.addAttribute("username", pending.username());
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "The new passwords do not match.");
            return "sign-in-password";
        }
        try {
            signInService.changePassword(pending.encryptedSecret(), currentPassword, newPassword);
        } catch (SignInThrottledException e) {
            PendingPhilterSignIn.clear(session);
            return "redirect:/login?locked";
        } catch (ClientException e) {
            if (e.getStatusCode() == 400) {
                model.addAttribute("error", "Philter did not accept the new password. It must be at least "
                        + "16 characters and differ from the current one.");
            } else {
                model.addAttribute("error", "The password was not changed. Check the current password.");
            }
            return "sign-in-password";
        } catch (UnauthorizedException e) {
            model.addAttribute("error", "The password was not changed. Check the current password, "
                    + "or sign in again.");
            return "sign-in-password";
        } catch (Exception e) {
            PendingPhilterSignIn.clear(session);
            return "redirect:" + loginFor(flow.refused(pending.username(), e), "error");
        }
        PendingPhilterSignIn.clear(session);
        return "redirect:/login?passwordChanged";
    }

    @GetMapping("/mfa-enrollment")
    public String mfaEnrollment(final HttpSession session) {
        return PendingPhilterSignIn.from(session, Step.MFA_ENROLLMENT) == null
                ? "redirect:/login" : "sign-in-mfa-enrollment";
    }

    /** Starts enrollment with the current password and shows the authenticator QR code once. */
    @PostMapping("/mfa-enrollment/start")
    public String startMfaEnrollment(@RequestParam("currentPassword") final String currentPassword,
                                     final HttpSession session,
                                     final Model model) {
        final PendingPhilterSignIn pending = PendingPhilterSignIn.from(session, Step.MFA_ENROLLMENT);
        if (pending == null) {
            return "redirect:/login";
        }
        final MfaEnrollment enrollment;
        try {
            enrollment = signInService.startMfaEnrollment(pending.encryptedSecret(), currentPassword);
        } catch (SignInThrottledException e) {
            PendingPhilterSignIn.clear(session);
            return "redirect:/login?locked";
        } catch (ClientException | UnauthorizedException e) {
            model.addAttribute("error", "Enrollment did not start. Check the password, or sign in again.");
            return "sign-in-mfa-enrollment";
        } catch (Exception e) {
            PendingPhilterSignIn.clear(session);
            return "redirect:" + loginFor(flow.refused(pending.username(), e), "error");
        }
        model.addAttribute("qrCodeDataUri", qrCodes.dataUri(enrollment.getOtpauthUri()));
        model.addAttribute("secret", enrollment.getSecret());
        return "sign-in-mfa-enrollment";
    }

    @PostMapping("/mfa-enrollment/confirm")
    public String confirmMfaEnrollment(@RequestParam("code") final String code,
                                       final HttpSession session,
                                       final Model model) {
        final PendingPhilterSignIn pending = PendingPhilterSignIn.from(session, Step.MFA_ENROLLMENT);
        if (pending == null) {
            return "redirect:/login";
        }
        try {
            signInService.confirmMfaEnrollment(pending.encryptedSecret(), code.trim());
        } catch (ClientException | UnauthorizedException e) {
            model.addAttribute("error", "The code was not accepted. Start again with your password "
                    + "and scan the new QR code.");
            return "sign-in-mfa-enrollment";
        } catch (Exception e) {
            PendingPhilterSignIn.clear(session);
            return "redirect:" + loginFor(flow.refused(pending.username(), e), "error");
        }
        PendingPhilterSignIn.clear(session);
        return "redirect:/login?mfaEnrolled";
    }

    /**
     * Signs the person in to Philter Workspace as the sign-in form does: under a new session id and a new CSRF
     * token, since both predate sign-in, and registered so the person's sessions can be expired.
     */
    private void signIn(final PhilterSessionAuthentication authentication,
                        final HttpServletRequest request,
                        final HttpServletResponse response) {
        request.changeSessionId();
        csrfTokenRepository.saveToken(null, request, response);
        sessionRegistry.registerNewSession(request.getSession().getId(), authentication.getPrincipal());
        ai.philterd.workspace.webapp.security.PhilterSessionValidationFilter.recordCheck(request.getSession());
        final SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private static String loginFor(final AuthenticationException e, final String badCredentialsParam) {
        if (e instanceof LockedException) {
            return "/login?locked";
        }
        if (e instanceof AuthenticationServiceException) {
            return "/login?unavailable";
        }
        if (e instanceof BadCredentialsException) {
            return "/login?" + badCredentialsParam;
        }
        return "/login?error";
    }
}
