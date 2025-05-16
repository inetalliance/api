package net.inetalliance.sonar.api;

import com.callgrove.obj.Opportunity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.val;
import net.inetalliance.angular.AngularServlet;
import net.inetalliance.angular.exception.BadRequestException;
import net.inetalliance.angular.exception.NotFoundException;
import net.inetalliance.potion.Locator;
import net.inetalliance.potion.info.Info;
import net.inetalliance.types.json.Json;
import net.inetalliance.types.json.JsonInteger;
import net.inetalliance.types.json.JsonList;
import net.inetalliance.types.json.JsonMap;

import java.util.Collection;
import java.util.regex.Pattern;

import static com.callgrove.obj.WizardAnswer.withOpportunity;
import static com.callgrove.obj.WizardAnswer.withQuestion;
import static net.inetalliance.sql.OrderBy.Direction.DESCENDING;

@WebServlet("/api/wizardAnswer/*")
public class WizardAnswer
        extends AngularServlet {

    private static final Pattern pattern = Pattern
            .compile("/api/wizardAnswer/(\\d+)(?:/question/(\\d+))?");

    private static Json toJson(com.callgrove.obj.WizardAnswer wiz) {
        return new JsonInteger(wiz.answer);
    }

    protected void get(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val matcher = pattern.matcher(request.getRequestURI());
        if (matcher.matches()) {
            val o = Info.$(Opportunity.class).lookup(matcher.group(1));
            if (o == null) {
                throw new NotFoundException("could not find opportunity with id %s", matcher.group(1));
            }

            respond(response, JsonList.collect(Locator.$$(withOpportunity(o)), WizardAnswer::toJson));
        } else {
            throw new BadRequestException("request should match %s", pattern.pattern());
        }
    }

    @Override
    protected void post(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val matcher = pattern.matcher(request.getRequestURI());
        if (matcher.matches()) {
            val o = Info.$(Opportunity.class).lookup(matcher.group(1));
            if (o == null) {
                throw new NotFoundException("could not find opportunity with id %s", matcher.group(1));
            }
            val data = JsonMap.parse(request.getInputStream());
            final Collection<Integer> answers = data.getList("answers").stream().map(Json::toInteger)
                    .toList();
            val question = Integer.parseInt(matcher.group(2));

            // delete existing
            val existing =
                    Locator.$$(withOpportunity(o).and(withQuestion(question)));
            existing.forEach(e -> Locator.delete(request.getRemoteUser(), e));

            // create new
            for (final int answerId : answers) {
                val answer = new com.callgrove.obj.WizardAnswer(o,
                        answerId);
                answer.setQuestion(question);
                Locator.create(request.getRemoteUser(), answer);
            }

            respond(response, JsonList.collect(Locator.$$(withOpportunity(o)), WizardAnswer::toJson));
        } else {
            throw new BadRequestException("request should match %s", pattern.pattern());
        }
    }

    @Override
    protected void delete(final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        val matcher = pattern.matcher(request.getRequestURI());
        if (matcher.matches()) {
            val o = Info.$(Opportunity.class).lookup(matcher.group(1));
            if (o == null) {
                throw new NotFoundException("could not find opportunity with id %s", matcher.group(1));
            }
            val lastAnswer = Locator.$1(withOpportunity(o, DESCENDING));

            // delete existing
            val existing =
                    Locator.$$(withOpportunity(o).and(withQuestion(lastAnswer.getQuestion())));
            existing.forEach(e -> Locator.delete(request.getRemoteUser(), e));

            respond(response, JsonList.collect(Locator.$$(withOpportunity(o)), WizardAnswer::toJson));
        } else {
            throw new BadRequestException("request should match %s", pattern.pattern());
        }
    }
}
