package net.inetalliance.sonar;

import javax.servlet.annotation.WebFilter;

@WebFilter({"/events", "/api/*", "/reporting/auth/*"})
public class AuthFilter
		extends net.inetalliance.angular.auth.AuthFilter {
}
