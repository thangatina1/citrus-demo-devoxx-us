/*
 * Copyright 2006-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.consol.citrus.demo.voting.integration;

import com.consol.citrus.annotations.CitrusEndpoint;
import com.consol.citrus.annotations.CitrusResource;
import com.consol.citrus.dsl.runner.TestRunner;
import com.consol.citrus.http.client.HttpClient;
import com.consol.citrus.jms.endpoint.JmsEndpoint;
import com.consol.citrus.mail.message.CitrusMailMessageHeaders;
import com.consol.citrus.mail.server.MailServer;
import com.consol.citrus.message.MessageType;
import cucumber.api.DataTable;
import cucumber.api.java.en.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;

/**
 * @author Christoph Deppisch
 */
public class VotingIntegrationSteps {

    @CitrusEndpoint
    private HttpClient votingClient;

    @CitrusEndpoint
    private MailServer mailServer;

    @CitrusEndpoint
    private JmsEndpoint reportingEndpoint;

    @CitrusResource
    private TestRunner runner;

    @Given("^New voting \"([^\"]*)\"$")
    public void newVoting(String title) {
        runner.variable("id", "citrus:randomUUID()");
        runner.variable("title", title);
        runner.variable("options", buildOptionsAsJsonArray("yes:no"));
        runner.variable("closed", false);
        runner.variable("report", false);
    }

    @Given("^(?:the )?voting options are \"([^\"]*)\"$")
    public void votingOptions(String options) {
        runner.variable("options", buildOptionsAsJsonArray(options));
    }

    @Given("^(?:the )?reporting is enabled$")
    public void reportingIsEnabled() {
        runner.variable("report", true);
    }

    @When("^(?:I|client) creates? the voting$")
    public void createVoting() {
        runner.http(builder -> builder.client(votingClient)
            .send()
            .post("/voting")
            .contentType("application/json")
            .payload("{ \"id\": \"${id}\", \"title\": \"${title}\", \"options\": ${options}, \"report\": ${report} }"));

        runner.http(builder -> builder.client(votingClient)
            .receive()
            .response(HttpStatus.OK)
            .messageType(MessageType.JSON));
    }

    @When("^(?:I|client) votes? for \"([^\"]*)\"$")
    public void voteFor(String option) {
        runner.http(builder -> builder.client(votingClient)
                .send()
                .put("voting/${id}/" + option));

        runner.http(builder -> builder.client(votingClient)
                .receive()
                .response(HttpStatus.OK));
    }

    @When("^(?:I|client) votes? for \"([^\"]*)\" (\\d+) times$")
    public void voteForTimes(String option, int times) {
        for (int i = 1; i <= times; i++) {
            voteFor(option);
        }
    }

    @When("^(?:I|client) closes? the voting$")
    public void closeVoting() {
        runner.createVariable("closed", "true");

        runner.http(builder -> builder.client(votingClient)
            .send()
            .put("/voting/${id}/close"));

        runner.http(builder -> builder.client(votingClient)
            .receive()
            .response(HttpStatus.OK));

    }

    @Then("^(?:I|client) should be able to get the voting \"([^\"]*)\"$")
    public void shouldGetById(String title) {
        runner.createVariable("title", title);
        shouldGetVoting();
    }

    @Then("^(?:I|client) should be able to get the voting$")
    public void shouldGetVoting() {
        runner.http(builder -> builder.client(votingClient)
                .send()
                .get("/voting/${id}")
                .accept("application/json"));

        runner.http(builder -> builder.client(votingClient)
                .receive()
                .response(HttpStatus.OK)
                .messageType(MessageType.JSON)
                .payload("{ \"id\": \"${id}\", \"title\": \"${title}\", \"options\": ${options}, \"closed\": ${closed}, \"report\": ${report} }"));
    }

    @Then("^(?:the )?reporting should receive vote results$")
    public void shouldReceiveReport(DataTable dataTable) {
        runner.createVariable("results", buildOptionsAsJsonArray(dataTable));

        runner.receive(builder -> builder.endpoint(reportingEndpoint)
                .messageType(MessageType.JSON)
                .payload("{ \"id\": \"${id}\", \"title\": \"${title}\", \"options\": ${results}, \"closed\": ${closed}, \"report\": ${report} }"));
    }

    @Then("^(?:the )?participants should receive reporting mail$")
    public void shouldReceiveReportingMail(String text) {
        runner.createVariable("mailBody", text);

        runner.receive(builder -> builder.endpoint(mailServer)
                .payload(new ClassPathResource("templates/mail.xml"))
                .header(CitrusMailMessageHeaders.MAIL_SUBJECT, "Voting results")
                .header(CitrusMailMessageHeaders.MAIL_FROM, "voting@example.org")
                .header(CitrusMailMessageHeaders.MAIL_TO, "participants@example.org"));
    }

    @Then("^(?:the )?list of votings should contain \"([^\"]*)\"$")
    public void listOfVotingsShouldContain(String title) {
        runner.http(builder -> builder.client(votingClient)
            .send()
            .get("/voting")
            .accept("application/json"));

        runner.http(builder -> builder.client(votingClient)
            .receive()
            .response(HttpStatus.OK)
            .messageType(MessageType.JSON)
            .validate("$..title.toString()", containsString(title)));
    }

    @Then("^(?:the )?votes should be$")
    public void votesShouldBe(DataTable dataTable) {
        runner.createVariable("options", buildOptionsAsJsonArray(dataTable));
        shouldGetVoting();
    }

    @Then("^(?:the )?top vote should be \"([^\"]*)\"$")
    public void topVoteShouldBe(String option) {
        runner.http(builder -> builder.client(votingClient)
                .send()
                .get("/voting/${id}/top")
                .accept("application/json"));

        runner.http(builder -> builder.client(votingClient)
                .receive()
                .response(HttpStatus.OK)
                .messageType(MessageType.JSON)
                .payload("{ \"name\": \"" + option + "\", \"votes\": \"@ignore@\" }"));
    }

    /**
     * Builds proper Json array from data table containing option names and votes.
     * @param dataTable
     * @return
     */
    private String buildOptionsAsJsonArray(DataTable dataTable) {
        StringBuilder optionsExpression = new StringBuilder();
        Map<String, String> variables = dataTable.asMap(String.class, String.class);
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            optionsExpression.append(entry.getKey()).append("(").append(entry.getValue()).append("):");
        }

        return buildOptionsAsJsonArray(optionsExpression.toString().substring(0, optionsExpression.length() - 1));
    }

    /**
     * Builds proper Json array from options colon delimited list.
     * @param optionsExpression
     * @return
     */
    private String buildOptionsAsJsonArray(String optionsExpression) {
        String[] options = optionsExpression.split(":");
        StringBuilder optionsJson = new StringBuilder();

        optionsJson.append("[");
        for (String option : options) {
            String votes = "0";
            if (option.contains("(") && option.endsWith(")")) {
                votes = option.substring(option.indexOf("(") + 1, option.length() - 1);
                option = option.substring(0, option.indexOf("("));
            }

            optionsJson.append("{ \"name\": \"").append(option).append("\", \"votes\": ").append(votes).append(" }");
        }
        optionsJson.append("]");

        return optionsJson.toString().replaceAll("\\}\\{", "}, {");
    }
}
