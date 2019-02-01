package net.inetalliance.sonar.api;

import com.callgrove.obj.ProductLine;
import net.inetalliance.sonar.ListableModel;

import javax.servlet.annotation.WebServlet;

@WebServlet({"/api/productLine/*", "/reporting/productLine/*"})
public class ProductLines
	extends ListableModel.Named<ProductLine> {
	public ProductLines() {
		super(ProductLine.class);
	}
}


