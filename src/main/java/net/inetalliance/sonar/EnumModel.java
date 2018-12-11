package net.inetalliance.sonar;

import javax.servlet.annotation.WebServlet;

@WebServlet("/api/enum/*")
public class EnumModel extends net.inetalliance.angular.EnumModel{
	public EnumModel() {
		super();
	}
}
