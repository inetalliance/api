package net.inetalliance.sonar.api;

import jakarta.servlet.annotation.WebServlet;

@WebServlet("/api/enum/*")
public class EnumModel
    extends net.inetalliance.angular.EnumModel {

  public EnumModel() {
    super();
  }
}
