package net.inetalliance.sonar;

import com.callgrove.obj.ObjectionCategory;
import net.inetalliance.angular.list.Listable;

import javax.servlet.annotation.WebServlet;

@WebServlet("/api/objectionCategory/*")
public class ObjectionCategories
		extends ListableModel.Named<ObjectionCategory>
		implements Listable<ObjectionCategory> {

	public ObjectionCategories() {
		super(ObjectionCategory.class);
	}

}
