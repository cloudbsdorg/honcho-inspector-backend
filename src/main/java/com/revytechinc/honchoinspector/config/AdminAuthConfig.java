package com.revytechinc.honchoinspector.config;

import com.revytechinc.honchoinspector.auth.AdminAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminAuthConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor admin;

    public AdminAuthConfig(AdminAuthInterceptor admin) {
        this.admin = admin;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(admin).addPathPatterns("/api/**");
    }
}
