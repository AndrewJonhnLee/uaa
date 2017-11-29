package org.cloudfoundry.identity.uaa.login;

import com.google.zxing.WriterException;
import com.warrenstrange.googleauth.GoogleAuthenticatorException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.mfa.GoogleAuthenticatorAdapter;
import org.cloudfoundry.identity.uaa.mfa.MfaProvider;
import org.cloudfoundry.identity.uaa.mfa.MfaProviderProvisioning;
import org.cloudfoundry.identity.uaa.mfa.UserGoogleMfaCredentialsProvisioning;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Controller
public class TotpEndpoint {
    public static final String MFA_VALIDATE_USER = "MFA_VALIDATE_USER";

    private UserGoogleMfaCredentialsProvisioning userGoogleMfaCredentialsProvisioning;
    private MfaProviderProvisioning mfaProviderProvisioning;
    private Log logger = LogFactory.getLog(TotpEndpoint.class);

    private GoogleAuthenticatorAdapter googleAuthenticatorService;
    private String mfaCompleteUrl = "/login/mfa/completed";

    public void setMfaCompleteUrl(String mfaCompleteUrl) {
        this.mfaCompleteUrl = mfaCompleteUrl;
    }

    @RequestMapping(value = {"/login/mfa/register"}, method = RequestMethod.GET)
    public String generateQrUrl(HttpSession session, Model model) throws NoSuchAlgorithmException, WriterException, IOException, UaaPrincipalIsNotInSession {

        UaaPrincipal uaaPrincipal = getSessionAuthPrincipal();

        String providerName = IdentityZoneHolder.get().getConfig().getMfaConfig().getProviderName();
        MfaProvider provider = mfaProviderProvisioning.retrieveByName(providerName, IdentityZoneHolder.get().getId());

        if(userGoogleMfaCredentialsProvisioning.activeUserCredentialExists(uaaPrincipal.getId(), provider.getId())) {
            return "redirect:/login/mfa/verify";
        } else {
            String url = googleAuthenticatorService.getOtpAuthURL(provider.getConfig().getIssuer(), uaaPrincipal.getId(), uaaPrincipal.getName());
            model.addAttribute("qrurl", url);
            model.addAttribute("identity_zone", IdentityZoneHolder.get().getName());

            return "mfa/qr_code";
        }
    }

    @RequestMapping(value = {"/login/mfa/manual"}, method = RequestMethod.GET)
    public String manualRegistration(HttpSession session, Model model) throws UaaPrincipalIsNotInSession {
        UaaPrincipal uaaPrincipal = getSessionAuthPrincipal();
        String providerName = IdentityZoneHolder.get().getConfig().getMfaConfig().getProviderName();
        MfaProvider provider = mfaProviderProvisioning.retrieveByName(providerName, IdentityZoneHolder.get().getId());

        if(userGoogleMfaCredentialsProvisioning.activeUserCredentialExists(uaaPrincipal.getId(), provider.getId())) {
            return "redirect:/login/mfa/verify";
        } else {
            model.addAttribute("issuer", provider.getConfig().getIssuer());
            model.addAttribute("username", uaaPrincipal.getName());
            model.addAttribute("mfa_secret", googleAuthenticatorService.getOtpSecret(uaaPrincipal.getId()));
            model.addAttribute("identity_zone", IdentityZoneHolder.get().getName());
            return "mfa/manual_registration";
        }

    }

    @RequestMapping(value = {"/login/mfa/verify"}, method = RequestMethod.GET)
    public ModelAndView totpAuthorize(HttpSession session, Model model) throws UaaPrincipalIsNotInSession {
        UaaPrincipal uaaPrincipal = getSessionAuthPrincipal();
        return renderEnterCodePage(model, uaaPrincipal);

    }

    @RequestMapping(value = {"/login/mfa/verify.do"}, method = RequestMethod.POST)
    public ModelAndView validateCode(Model model,
                             HttpSession session,
                             HttpServletRequest request,
                             HttpServletResponse response,
                             @RequestParam("code") String code)
            throws NoSuchAlgorithmException, IOException, UaaPrincipalIsNotInSession {
        UaaAuthentication uaaAuth = getUaaAuthentication();
        UaaPrincipal uaaPrincipal = getSessionAuthPrincipal();
        try {
            Integer codeValue = Integer.valueOf(code);
            if(googleAuthenticatorService.isValidCode(uaaPrincipal.getId(), codeValue)) {
                userGoogleMfaCredentialsProvisioning.persistCredentials();
                Set<String> authMethods = new HashSet<>(uaaAuth.getAuthenticationMethods());
                authMethods.addAll(Arrays.asList("otp", "mfa"));
                uaaAuth.setAuthenticationMethods(authMethods);
                return new ModelAndView(new RedirectView(mfaCompleteUrl, true));
            }
            logger.debug("Code authorization failed for user: " + uaaPrincipal.getId());
            model.addAttribute("error", "Incorrect code, please try again.");
        } catch (NumberFormatException|GoogleAuthenticatorException e) {
            logger.debug("Error validating the code for user: " + uaaPrincipal.getId() + ". Error: " + e.getMessage());
            model.addAttribute("error", "Incorrect code, please try again.");
        }
        return renderEnterCodePage(model, uaaPrincipal);
    }

    public void setUserGoogleMfaCredentialsProvisioning(UserGoogleMfaCredentialsProvisioning userGoogleMfaCredentialsProvisioning) {
        this.userGoogleMfaCredentialsProvisioning = userGoogleMfaCredentialsProvisioning;
    }

    public void setMfaProviderProvisioning(MfaProviderProvisioning mfaProviderProvisioning) {
        this.mfaProviderProvisioning = mfaProviderProvisioning;
    }

    public void setGoogleAuthenticatorService(GoogleAuthenticatorAdapter googleAuthenticatorService) {
        this.googleAuthenticatorService = googleAuthenticatorService;
    }

    @ExceptionHandler(UaaPrincipalIsNotInSession.class)
    public ModelAndView handleUaaPrincipalIsNotInSession() {
        return new ModelAndView("redirect:/login", Collections.emptyMap());
    }

    private ModelAndView renderEnterCodePage(Model model, UaaPrincipal uaaPrincipal) {
        model.addAttribute("is_first_time_user", userGoogleMfaCredentialsProvisioning.isFirstTimeMFAUser(uaaPrincipal));
        model.addAttribute("identity_zone", IdentityZoneHolder.get().getName());
        return new ModelAndView("mfa/enter_code", model.asMap());
    }

    private UaaPrincipal getSessionAuthPrincipal() throws UaaPrincipalIsNotInSession {
        UaaAuthentication uaaAuth = getUaaAuthentication();
        if(uaaAuth != null) {
            UaaPrincipal principal = uaaAuth.getPrincipal();
            if(principal != null) {
                return principal;
            }
        }
        throw new UaaPrincipalIsNotInSession();
    }

    private UaaAuthentication getUaaAuthentication() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a instanceof UaaAuthentication ? (UaaAuthentication)a : null;
    }

    public class UaaPrincipalIsNotInSession extends Exception {}
}
