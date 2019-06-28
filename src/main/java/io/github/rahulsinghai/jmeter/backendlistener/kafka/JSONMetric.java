/*
 * Copyright 2019 Rahul Singhai.
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

package io.github.rahulsinghai.jmeter.backendlistener.kafka;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JSONMetric {

  private static final Logger logger = LoggerFactory.getLogger(JSONMetric.class);
  private SampleResult sampleResult;
  private String kafkaTestMode;
  private String kafkaTimestamp;
  private int ciBuildNumber;
  private HashMap<String, Object> json;
  private Set<String> fields;
  private boolean allReqHeaders;
  private boolean allResHeaders;

  public JSONMetric(
      SampleResult sr,
      String testMode,
      String timeStamp,
      int buildNumber,
      boolean parseReqHeaders,
      boolean parseResHeaders,
      Set<String> fields) {
    this.sampleResult = sr;
    this.kafkaTestMode = testMode.trim();
    this.kafkaTimestamp = timeStamp.trim();
    this.ciBuildNumber = buildNumber;
    this.json = new HashMap<>();
    this.allReqHeaders = parseReqHeaders;
    this.allResHeaders = parseResHeaders;
    this.fields = fields;
  }

  /**
   * This method returns the current metric as a Map(String, Object) for the provided sampleResult
   *
   * @param context BackendListenerContext
   * @return a JSON Object as Map(String, Object)
   * @throws UnknownHostException If unable to determine injector host name.
   */
  public Map<String, Object> getMetric(BackendListenerContext context) throws UnknownHostException {
    SimpleDateFormat sdf = new SimpleDateFormat(this.kafkaTimestamp);

    // add all the default SampleResult parameters
    addFilteredJSON("AllThreads", this.sampleResult.getAllThreads());
    addFilteredJSON("BodySize", this.sampleResult.getBodySizeAsLong());
    addFilteredJSON("Bytes", this.sampleResult.getBytesAsLong());
    addFilteredJSON("SentBytes", this.sampleResult.getSentBytes());
    addFilteredJSON("ConnectTime", this.sampleResult.getConnectTime());
    addFilteredJSON("ContentType", this.sampleResult.getContentType());
    addFilteredJSON("DataType", this.sampleResult.getDataType());
    addFilteredJSON("ErrorCount", this.sampleResult.getErrorCount());
    addFilteredJSON("GrpThreads", this.sampleResult.getGroupThreads());
    addFilteredJSON("IdleTime", this.sampleResult.getIdleTime());
    addFilteredJSON("Latency", this.sampleResult.getLatency());
    addFilteredJSON("ResponseTime", this.sampleResult.getTime());
    addFilteredJSON("SampleCount", this.sampleResult.getSampleCount());
    addFilteredJSON("SampleLabel", this.sampleResult.getSampleLabel());
    addFilteredJSON("ThreadName", this.sampleResult.getThreadName());
    addFilteredJSON("URL", this.sampleResult.getURL());
    addFilteredJSON("ResponseCode", this.sampleResult.getResponseCode());
    addFilteredJSON("TestStartTime", JMeterContextService.getTestStartTime());
    addFilteredJSON("SampleStartTime", sdf.format(new Date(this.sampleResult.getStartTime())));
    addFilteredJSON("SampleEndTime", sdf.format(new Date(this.sampleResult.getEndTime())));
    addFilteredJSON("Timestamp", sdf.format(new Date(this.sampleResult.getTimeStamp())));
    addFilteredJSON("InjectorHostname", InetAddress.getLocalHost().getHostName());

    // Add the details according to the mode that is set
    switch (this.kafkaTestMode) {
      case "debug":
      case "error":
        addDetails();
        break;
      case "info":
        if (!this.sampleResult.isSuccessful()) {
          addDetails();
        }
        break;
      default:
        break;
    }

    addAssertions();
    addElapsedTime(sdf);
    addCustomFields(context);
    parseHeadersAsJsonProps(this.allReqHeaders, this.allResHeaders);

    return this.json;
  }

  /** This method adds all the assertions for the current sampleResult */
  private void addAssertions() {
    AssertionResult[] assertionResults = this.sampleResult.getAssertionResults();
    if (assertionResults != null) {
      @SuppressWarnings("unchecked")
      HashMap<String, Object>[] assertionArray = new HashMap[assertionResults.length];
      int i = 0;
      String failureMessage = "";
      boolean isFailure = false;
      for (AssertionResult assertionResult : assertionResults) {
        HashMap<String, Object> assertionMap = new HashMap<>();
        boolean failure = assertionResult.isFailure() || assertionResult.isError();
        isFailure = isFailure || assertionResult.isFailure() || assertionResult.isError();
        assertionMap.put("failure", failure);
        assertionMap.put("failureMessage", assertionResult.getFailureMessage());
        failureMessage += assertionResult.getFailureMessage() + "\n";
        assertionMap.put("name", assertionResult.getName());
        assertionArray[i] = assertionMap;
        i++;
      }
      addFilteredJSON("AssertionResults", assertionArray);
      addFilteredJSON("FailureMessage", failureMessage);
      addFilteredJSON("Success", !isFailure);
    }
  }

  /**
   * This method adds the ElapsedTime as a key:value pair in the JSON object. Also, depending on
   * whether or not the tests were launched from a CI tool (i.e Jenkins), it will add a hard-coded
   * version of the ElapsedTime for results comparison purposes
   *
   * @param sdf SimpleDateFormat
   */
  private void addElapsedTime(SimpleDateFormat sdf) {
    Date elapsedTime;

    if (this.ciBuildNumber != 0) {
      elapsedTime = getElapsedTime(true);
      addFilteredJSON("BuildNumber", this.ciBuildNumber);

      if (elapsedTime != null) {
        addFilteredJSON("ElapsedTimeComparison", sdf.format(elapsedTime));
      }
    }

    elapsedTime = getElapsedTime(false);
    if (elapsedTime != null) {
      addFilteredJSON("ElapsedTime", sdf.format(elapsedTime));
    }
  }

  /**
   * Methods that add all custom fields added by the user in the Backend Listener's GUI panel
   *
   * @param context BackendListenerContext
   */
  private void addCustomFields(BackendListenerContext context) {
    Iterator<String> pluginParameters = context.getParameterNamesIterator();
    while (pluginParameters.hasNext()) {
      String parameterName = pluginParameters.next();

      if (!parameterName.startsWith(KafkaBackendClient.SERVICE_NAME_PREFIX)
          && !context.getParameter(parameterName).trim().equals("")) {
        String parameter = context.getParameter(parameterName).trim();

        try {
          addFilteredJSON(parameterName, Long.parseLong(parameter));
        } catch (Exception e) {
          if (logger.isDebugEnabled()) {
            logger.debug("Cannot convert custom field to number");
          }
          addFilteredJSON(parameterName, context.getParameter(parameterName).trim());
        }
      }
    }
  }

  /** Method that adds the request and response's body/headers */
  private void addDetails() {
    addFilteredJSON("RequestHeaders", this.sampleResult.getRequestHeaders());
    addFilteredJSON("RequestBody", this.sampleResult.getSamplerData());
    addFilteredJSON("ResponseHeaders", this.sampleResult.getResponseHeaders());
    addFilteredJSON("ResponseBody", this.sampleResult.getResponseDataAsString());
    addFilteredJSON("ResponseMessage", this.sampleResult.getResponseMessage());
  }

  /**
   * This method will parse the headers and look for custom variables passed through as header. It
   * can also separate all headers into different Kafka document properties by passing "true". This
   * is a work-around the native behaviour of JMeter where variables are not accessible within the
   * backend listener.
   *
   * @param allReqHeaders boolean to determine if the user wants to separate ALL request headers
   *     into different ES JSON properties.
   * @param allResHeaders boolean to determine if the user wants to separate ALL response headers
   *     into different ES JSON properties.
   *     <p>NOTE: This will be fixed as soon as a patch comes in for JMeter to change the behaviour.
   */
  private void parseHeadersAsJsonProps(boolean allReqHeaders, boolean allResHeaders) {
    LinkedList<String[]> headersArrayList = new LinkedList<>();

    if (allReqHeaders) {
      headersArrayList.add(this.sampleResult.getRequestHeaders().split("\n"));
    }

    if (allResHeaders) {
      headersArrayList.add(this.sampleResult.getResponseHeaders().split("\n"));
    }

    for (String[] lines : headersArrayList) {
      for (String line : lines) {
        String[] header = line.split(":", 2);

        // if not all req headers and header contains special X-tag
        if (header.length > 1) {
          if (!this.allReqHeaders && header[0].startsWith("X-es-backend")) {
            this.json.put(header[0].replaceAll("es-", "").trim(), header[1].trim());
          } else {
            this.json.put(header[0].replaceAll("es-", "").trim(), header[1].trim());
          }
        }
      }
    }
  }

  /**
   * Adds a given key-value pair to JSON if the key is contained in the field filter or in case of
   * empty field filter
   */
  private void addFilteredJSON(String key, Object value) {
    if (this.fields.size() == 0 || this.fields.contains(key.toLowerCase())) {
      this.json.put(key, value);
    }
  }

  /**
   * This method is meant to return the elapsed time in a human readable format. The purpose of this
   * is mostly for build comparison in Kibana. By doing this, the user is able to set the X-axis of
   * his graph to this date and split the series by build numbers. It allows him to overlap test
   * results and see if there is regression or not.
   *
   * @param forBuildComparison boolean to determine if there is CI (continuous integration) or not
   * @return The elapsed time in YYYY-MM-dd HH:mm:ss format
   */
  public Date getElapsedTime(boolean forBuildComparison) {
    String sElapsed;
    // Calculate the elapsed time (Starting from midnight on a random day - enables us to compare of
    // two loads over their duration)
    long start = JMeterContextService.getTestStartTime();
    long end = System.currentTimeMillis();
    long elapsed = (end - start);
    long minutes = (elapsed / 1000) / 60;
    long seconds = (elapsed / 1000) % 60;

    Calendar cal = Calendar.getInstance();
    cal.set(
        Calendar.HOUR_OF_DAY,
        0); // If there is more than an hour of data, the number of minutes/seconds will increment
    // this
    cal.set(Calendar.MINUTE, (int) minutes);
    cal.set(Calendar.SECOND, (int) seconds);

    if (forBuildComparison) {
      sElapsed =
          String.format(
              "2017-01-01 %02d:%02d:%02d",
              cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND));
    } else {
      sElapsed =
          String.format(
              "%s %02d:%02d:%02d",
              DateTimeFormatter.ofPattern("yyyy-mm-dd").format(LocalDateTime.now()),
              cal.get(Calendar.HOUR_OF_DAY),
              cal.get(Calendar.MINUTE),
              cal.get(Calendar.SECOND));
    }

    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-mm-dd HH:mm:ss");
    try {
      return formatter.parse(sElapsed);
    } catch (ParseException e) {
      logger.error("Unexpected error occurred computing elapsed date", e);
      return null;
    }
  }
}
