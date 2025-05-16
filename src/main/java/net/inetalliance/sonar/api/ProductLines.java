package net.inetalliance.sonar.api;

import com.callgrove.obj.ProductLine;
import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.inetalliance.sonar.ListableModel;

@WebServlet("/api/productLine/*")
public class ProductLines
    extends ListableModel.Named<ProductLine> {

  private final ScriptRoot scriptRoot;

  public ProductLines() {
    super(ProductLine.class);
    this.scriptRoot = new ScriptRoot();
  }

  @Override
  protected void service(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException {
    if (scriptRoot.getPattern().matcher(req.getRequestURI()).matches()) {
      scriptRoot.service(req, resp);
    } else {
      super.service(req, resp);
    }
  }
}


