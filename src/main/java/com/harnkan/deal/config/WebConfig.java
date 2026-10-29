package com.harnkan.deal.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.harnkan.deal.web.CurrentUserArgumentResolver;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final boolean jwtMode;

    public WebConfig(@Value("${auth.mode:gateway}") String authMode) {
        this.jwtMode = "jwt".equalsIgnoreCase(authMode);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver(jwtMode));
    }
}
