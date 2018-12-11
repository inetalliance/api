package net.inetalliance.sonar;

import com.callgrove.obj.ProductLine;

import javax.servlet.annotation.WebServlet;

@WebServlet({"/api/productLine/*", "/reporting/productLine/*"})
public class ProductLines
		extends ListableModel.Named<ProductLine> {
	public ProductLines() {
		super(ProductLine.class);
	}
}


