package net.inetalliance.sonar.api;

import jakarta.servlet.annotation.WebServlet;

@WebServlet("/api/model/*")
public class Model
    extends net.inetalliance.angular.Model {

  public Model() {
    super();
  }
}
