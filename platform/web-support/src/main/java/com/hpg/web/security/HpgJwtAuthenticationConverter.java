package com.hpg.web.security;

import com.hpg.common.security.TokenClaims;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

/** Maps the {@code roles} claim (e.g. {@code ["MERCHANT"]}) to Spring authorities ({@code ROLE_MERCHANT}). */
public class HpgJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(TokenClaims.ROLES);
        List<GrantedAuthority> authorities = roles == null ? List.of()
                : roles.stream().<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
