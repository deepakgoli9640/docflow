package com.hpg.web.security;

import com.hpg.common.error.BusinessException;
import com.hpg.common.error.CommonErrorCode;
import com.hpg.common.security.TokenClaims;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/** Resolves {@link MerchantPrincipal} controller parameters from the current JWT. */
public class MerchantPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return MerchantPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public MerchantPrincipal resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED, "A merchant bearer token is required.");
        }
        String merchantId = jwtAuth.getToken().getClaimAsString(TokenClaims.MERCHANT_ID);
        if (merchantId == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "This operation requires a merchant token.");
        }
        try {
            return new MerchantPrincipal(UUID.fromString(merchantId), jwtAuth.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "Token carries an invalid merchant id.");
        }
    }
}
