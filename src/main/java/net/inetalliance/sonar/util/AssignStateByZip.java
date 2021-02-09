package net.inetalliance.sonar.util;

import com.callgrove.Callgrove;
import com.callgrove.obj.AreaCodeTime;
import com.callgrove.obj.Opportunity;
import com.callgrove.types.SaleSource;
import net.inetalliance.cli.Cli;
import net.inetalliance.funky.StringFun;
import net.inetalliance.log.progress.ProgressMeter;
import net.inetalliance.potion.DbCli;
import net.inetalliance.potion.Locator;
import net.inetalliance.types.geopolitical.Country;
import net.inetalliance.types.geopolitical.canada.Division;
import net.inetalliance.types.geopolitical.us.State;

public class AssignStateByZip extends DbCli {
    @Override
    protected void exec() throws Throwable {
        Callgrove.register();
        var q = Opportunity.withState(null).and(Opportunity.withSaleSource(SaleSource.PHONE_CALL));
        var n = Locator.count(q);
        System.out.printf("There are %d opps with no state set%n", n);
        var meter = new ProgressMeter(n);
        Locator.forEach(q, o -> {
            meter.increment();
            var c = o.getContact();
            var shipping = c.getShipping();
            if (shipping != null) {
                var s = byZipCode(shipping.getPostalCode());
                Division d  = null;
                if (s == null && StringFun.isNotEmpty(shipping.getPhone())) {

                    var areaCode = AreaCodeTime.getAreaCodeTime(shipping.getPhone());
                    if(areaCode != null) {
                        if(areaCode.getCountry() == Country.UNITED_STATES) {
                            s = areaCode.getUsState();
                        } else {
                            d = areaCode.getCaDivision();
                        }
                    }
                }
                if (s != null || d != null) {
                    final var state = s;
                    final var division = d;
                    Locator.update(o.getContact(), "AssignStateByZip", copy -> {
                        copy.getShipping().setState(state);
                        copy.getShipping().setCanadaDivision(division);
                    });
                }
            }
        });
        System.out.printf("There are now %d opps with no state set%n", Locator.count(q));

    }

    private State byZipCode(final String postalCode) {
        if (StringFun.isEmpty(postalCode)) {
            return null;
        }
        try {
            int zipcode = Integer.parseInt(postalCode.substring(0, Integer.min(postalCode.length(), 5)));
            if (zipcode >= 35000 && zipcode <= 36999) {
                return State.ALABAMA;
            } else if (zipcode >= 99500 && zipcode <= 99999) {
                return State.ALASKA;
            } else if (zipcode >= 85000 && zipcode <= 86999) {
                return State.ARIZONA;
            } else if (zipcode >= 71600 && zipcode <= 72999) {
                return State.ARKANSAS;
            } else if (zipcode >= 90000 && zipcode <= 96699) {
                return State.CALIFORNIA;
            } else if (zipcode >= 80000 && zipcode <= 81999) {
                return State.COLORADO;
            } else if ((zipcode >= 6000 && zipcode <= 6389) || (zipcode >= 6391 && zipcode <= 6999)) {
                return State.CONNECTICUT;
            } else if (zipcode >= 19700 && zipcode <= 19999) {
                return State.DELAWARE;
            } else if (zipcode >= 32000 && zipcode <= 34999) {
                return State.FLORIDA;
            } else if ((zipcode >= 30000 && zipcode <= 31999) || (zipcode >= 39800 && zipcode <= 39999)) {
                return State.GEORGIA;
            } else if (zipcode >= 96700 && zipcode <= 96999) {
                return State.HAWAII;
            } else if (zipcode >= 83200 && zipcode <= 83999) {
                return State.IDAHO;
            } else if (zipcode >= 60000 && zipcode <= 62999) {
                return State.ILLINOIS;
            } else if (zipcode >= 46000 && zipcode <= 47999) {
                return State.INDIANA;
            } else if (zipcode >= 50000 && zipcode <= 52999) {
                return State.IOWA;
            } else if (zipcode >= 66000 && zipcode <= 67999) {
                return State.KANSAS;
            } else if (zipcode >= 40000 && zipcode <= 42999) {
                return State.KENTUCKY;
            } else if (zipcode >= 70000 && zipcode <= 71599) {
                return State.LOUISIANA;
            } else if (zipcode >= 3900 && zipcode <= 4999) {
                return State.MAINE;
            } else if (zipcode >= 20600 && zipcode <= 21999) {
                return State.MARYLAND;
            } else if ((zipcode >= 1000 && zipcode <= 2799) || (zipcode == 5501)) {
                return State.MASSACHUSETTS;
            } else if (zipcode >= 48000 && zipcode <= 49999) {
                return State.MICHIGAN;
            } else if (zipcode >= 55000 && zipcode <= 56899) {
                return State.MINNESOTA;
            } else if (zipcode >= 38600 && zipcode <= 39999) {
                return State.MISSISSIPPI;
            } else if (zipcode >= 63000 && zipcode <= 65999) {
                return State.MISSOURI;
            } else if (zipcode >= 59000 && zipcode <= 59999) {
                return State.MONTANA;
            } else if (zipcode >= 27000 && zipcode <= 28999) {
                return State.NORTH_CAROLINA;
            } else if (zipcode >= 58000 && zipcode <= 58999) {
                return State.NORTH_DAKOTA;
            } else if (zipcode >= 68000 && zipcode <= 69999) {
                return State.NEBRASKA;
            } else if (zipcode >= 88900 && zipcode <= 89999) {
                return State.NEVADA;
            } else if (zipcode >= 3000 && zipcode <= 3899) {
                return State.NEW_HAMPSHIRE;
            } else if (zipcode >= 7000 && zipcode <= 8999) {
                return State.NEW_JERSEY;
            } else if (zipcode >= 87000 && zipcode <= 88499) {
                return State.NEW_MEXICO;
            } else if ((zipcode >= 10000 && zipcode <= 14999) || (zipcode == 6390)) {
                return State.NEW_YORK;
            } else if (zipcode >= 43000 && zipcode <= 45999) {
                return State.OHIO;
            } else if ((zipcode >= 73000 && zipcode <= 73199) || (zipcode >= 73400 && zipcode <= 74999)) {
                return State.OKLAHOMA;
            } else if (zipcode >= 97000 && zipcode <= 97999) {
                return State.OREGON;
            } else if (zipcode >= 15000 && zipcode <= 19699) {
                return State.PENNSYLVANIA;
            } else if (zipcode >= 300 && zipcode <= 999) {
                return State.PUERTO_RICO;
            } else if (zipcode >= 2800 && zipcode <= 2999) {
                return State.RHODE_ISLAND;
            } else if (zipcode >= 29000 && zipcode <= 29999) {
                return State.SOUTH_CAROLINA;
            } else if (zipcode >= 57000 && zipcode <= 57999) {
                return State.SOUTH_DAKOTA;
            } else if (zipcode >= 37000 && zipcode <= 38599) {
                return State.TENNESSEE;
            } else if ((zipcode >= 75000 && zipcode <= 79999) || (zipcode >= 73301 && zipcode <= 73399) || (zipcode >= 88500 && zipcode <= 88599)) {
                return State.TEXAS;
            } else if (zipcode >= 84000 && zipcode <= 84999) {
                return State.UTAH;
            } else if (zipcode >= 5000 && zipcode <= 5999) {
                return State.VERMONT;
            } else if ((zipcode >= 20100 && zipcode <= 20199) || (zipcode >= 22000 && zipcode <= 24699) || (zipcode == 20598)) {
                return State.VIRGINIA;
            } else if ((zipcode >= 20000 && zipcode <= 20099) || (zipcode >= 20200 && zipcode <= 20599) || (zipcode >= 56900 && zipcode <= 56999)) {
                return State.DISTRICT_OF_COLUMBIA;
            } else if (zipcode >= 98000 && zipcode <= 99499) {
                return State.WASHINGTON;
            } else if (zipcode >= 24700 && zipcode <= 26999) {
                return State.WEST_VIRGINIA;
            } else if (zipcode >= 53000 && zipcode <= 54999) {
                return State.WISCONSIN;
            } else if (zipcode >= 82000 && zipcode <= 83199) {
                return State.WYOMING;
            } else {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }


    public static void main(String[] args) {
        Cli.run(new AssignStateByZip(), args);
    }
}
