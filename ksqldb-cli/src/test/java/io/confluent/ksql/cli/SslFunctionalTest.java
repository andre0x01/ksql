/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.cli;

import static io.confluent.ksql.serde.FormatFactory.JSON;
import static java.util.Collections.emptyMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.junit.internal.matchers.ThrowableCauseMatcher.hasCause;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;

import com.google.common.net.UrlEscapers;
import io.confluent.common.utils.IntegrationTest;
import io.confluent.ksql.integration.IntegrationTestHarness;
import io.confluent.ksql.integration.Retry;
import io.confluent.ksql.rest.client.KsqlRestClient;
import io.confluent.ksql.rest.client.KsqlRestClientException;
import io.confluent.ksql.rest.client.RestResponse;
import io.confluent.ksql.rest.server.TestKsqlRestApp;
import io.confluent.ksql.test.util.secure.ClientTrustStore;
import io.confluent.ksql.test.util.secure.ServerKeyStore;
import io.confluent.ksql.util.OrderDataProvider;
import io.confluent.rest.RestConfig;
import java.io.EOFException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import javax.ws.rs.ProcessingException;
import kafka.zookeeper.ZooKeeperClientException;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpStatus.Code;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory.Server;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;

@Category({IntegrationTest.class})
public class SslFunctionalTest {

  private static final String TOPIC_NAME = new OrderDataProvider().topicName();

  private static final String JSON_KSQL_REQUEST = UrlEscapers.urlFormParameterEscaper()
      .escape("{"
          + " \"ksql\": \"PRINT " + TOPIC_NAME + " FROM BEGINNING;\""
          + "}");

  public static final IntegrationTestHarness TEST_HARNESS = IntegrationTestHarness.build();

  private static final TestKsqlRestApp REST_APP = TestKsqlRestApp
      .builder(TEST_HARNESS::kafkaBootstrapServers)
      .withProperties(ServerKeyStore.keyStoreProps())
      .withProperty(RestConfig.LISTENERS_CONFIG, "https://localhost:0")
      .build();

  @ClassRule
  public static final RuleChain CHAIN = RuleChain
      .outerRule(Retry.of(3, ZooKeeperClientException.class, 3, TimeUnit.SECONDS))
      .around(TEST_HARNESS)
      .around(REST_APP);

  private Map<String, String> clientProps;
  private SslContextFactory sslContextFactory;

  @BeforeClass
  public static void classSetUp() {
    final OrderDataProvider dataProvider = new OrderDataProvider();
    TEST_HARNESS.getKafkaCluster().createTopics(TOPIC_NAME);
    TEST_HARNESS.produceRows(dataProvider.topicName(), dataProvider, JSON);
  }

  @Before
  public void setUp() {
    clientProps = Collections.emptyMap();
    sslContextFactory = new Server();
  }

  @Test
  public void shouldNotBeAbleToUseCliIfClientDoesNotTrustServerCert() {
    // When:
    final Exception e = assertThrows(
        KsqlRestClientException.class,
        () -> canMakeCliRequest()
    );

    // Then:
    assertThat(e.getCause(), (is(instanceOf(ProcessingException.class))));
    assertThat(e.getCause(), (hasCause(is(instanceOf(SSLHandshakeException.class)))));
  }

  @Test
  public void shouldBeAbleToUseCliOverHttps() {
    // Given:
    givenTrustStoreConfigured();

    // When:
    final Code result = canMakeCliRequest();

    // Then:
    assertThat(result, is(Code.OK));
  }

  @Test
  public void shouldNotBeAbleToUseWssIfClientDoesNotTrustServerCert() {
    // When:
    final Exception e = assertThrows(
        Exception.class,
        this::makeWsRequest
    );

    // Then:
    if (!(e instanceof EOFException)) { // Occasionally, get EOF exception.
      assertThat(e, instanceOf(SSLHandshakeException.class));
      assertThat(
          e.getCause().getCause().getMessage(),
          containsString("unable to find valid certification path to requested target")
      );
    }
  }

  @Test
  public void shouldBeAbleToUseWss() throws Exception {
    // Given:
    givenTrustStoreConfigured();

    // When:
    makeWsRequest();

    // Then: did not throw.
  }

  private void givenTrustStoreConfigured() {
    // HTTP:
    clientProps = ClientTrustStore.trustStoreProps();

    // WS:
    sslContextFactory.setTrustStorePath(ClientTrustStore.trustStorePath());
    sslContextFactory.setTrustStorePassword(ClientTrustStore.trustStorePassword());
    sslContextFactory.setEndpointIdentificationAlgorithm("");
  }

  private Code canMakeCliRequest() {
    final String serverAddress = REST_APP.getHttpsListener().toString();

    try (KsqlRestClient restClient = KsqlRestClient.create(
        serverAddress,
        emptyMap(),
        clientProps,
        Optional.empty()
    )) {
      final RestResponse<?> response = restClient.makeKsqlRequest("show topics;");
      if (response.isSuccessful()) {
        return Code.OK;
      }

      return HttpStatus.getCode(response.getErrorMessage().getErrorCode());
    }
  }

  private void makeWsRequest() throws Exception {
    final HttpClient httpClient = new HttpClient(sslContextFactory);
    httpClient.start();
    final WebSocketClient wsClient = new WebSocketClient(httpClient);
    wsClient.start();

    try {
      final ClientUpgradeRequest request = new ClientUpgradeRequest();
      final WebSocketListener listener = new WebSocketListener();
      final URI wsUri = REST_APP.getWssListener().resolve("/ws/query?request=" + JSON_KSQL_REQUEST);

      wsClient.connect(listener, wsUri, request);

      assertThat("Response received",
          listener.latch.await(30, TimeUnit.SECONDS), is(true));

      final Throwable error = listener.error.get();
      if (error != null) {
        throw (Exception) error;
      }
    } finally {
      wsClient.stop();
      httpClient.destroy();
    }
  }

  @WebSocket
  public static class WebSocketListener {

    private Session session;
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @SuppressWarnings("unused") // Invoked via reflection
    @OnWebSocketConnect
    public void onConnect(final Session session) {
      this.session = session;
    }

    @SuppressWarnings("unused") // Invoked via reflection
    @OnWebSocketError
    public void onError(final Throwable t) {
      error.compareAndSet(null, t);
      closeSilently();
      latch.countDown();
    }

    @SuppressWarnings("unused") // Invoked via reflection
    @OnWebSocketMessage
    public void onMessage(final String msg) {
      closeSilently();
      latch.countDown();
    }

    private void closeSilently() {
      try {
        if (session != null) {
          session.close();
        }
      } catch (final Exception e) {
        // meh
      }
    }
  }
}