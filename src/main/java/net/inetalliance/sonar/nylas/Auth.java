package net.inetalliance.sonar.nylas;

import javax.servlet.*;
import javax.servlet.annotation.*;
import javax.servlet.http.*;
import java.io.*;

@WebServlet("/nylas/auth")
public class Auth
		extends HttpServlet {

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		final String challenge = req.getParameter("challenge");
		try (final ServletOutputStream os = resp.getOutputStream()) {
			resp.setStatus(200);
			os.print(challenge);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		super.doPost(req, resp);
	}
}
