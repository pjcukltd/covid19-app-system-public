package uk.nhs.nhsx.virology;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.nhs.nhsx.core.Environment;
import uk.nhs.nhsx.core.EnvironmentKeys;
import uk.nhs.nhsx.core.HttpResponses;
import uk.nhs.nhsx.core.Jackson;
import uk.nhs.nhsx.core.SystemClock;
import uk.nhs.nhsx.core.auth.ApiName;
import uk.nhs.nhsx.core.auth.Authenticator;
import uk.nhs.nhsx.core.auth.ResponseSigner;
import uk.nhs.nhsx.core.routing.Routing;
import uk.nhs.nhsx.core.routing.Routing.*;
import uk.nhs.nhsx.core.routing.RoutingHandler;
import uk.nhs.nhsx.virology.exchange.CtaExchangeRequest;
import uk.nhs.nhsx.virology.lookup.VirologyLookupRequest;
import uk.nhs.nhsx.virology.order.VirologyRequestType;
import uk.nhs.nhsx.virology.order.TokensGenerator;
import uk.nhs.nhsx.virology.order.VirologyWebsiteConfig;
import uk.nhs.nhsx.virology.persistence.VirologyPersistenceService;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static uk.nhs.nhsx.core.Environment.EnvironmentKey.string;
import static uk.nhs.nhsx.core.Jackson.deserializeMaybe;
import static uk.nhs.nhsx.core.Jackson.deserializeMaybeLogInfo;
import static uk.nhs.nhsx.core.StandardSigning.signResponseWithKeyGivenInSsm;
import static uk.nhs.nhsx.core.auth.StandardAuthentication.awsAuthentication;
import static uk.nhs.nhsx.core.routing.Routing.*;
import static uk.nhs.nhsx.core.routing.StandardHandlers.withSignedResponses;

public class VirologySubmissionHandler extends RoutingHandler {

    private final Routing.Handler handler;

    private static final Logger logger = LogManager.getLogger(VirologySubmissionHandler.class);
    private static final Duration defaultDelayDuration = Duration.ofSeconds(1);

    public VirologySubmissionHandler() {
        this(Environment.fromSystem(), SystemClock.CLOCK, defaultDelayDuration);
    }

    public VirologySubmissionHandler(Environment environment, Supplier<Instant> clock, Duration throttleDuration) {
        this(
            environment, 
            awsAuthentication(ApiName.Mobile),
            signResponseWithKeyGivenInSsm(clock, environment),
            virologyService(clock, environment),
            websiteConfig(environment),
            throttleDuration
        );
    }

    public VirologySubmissionHandler(Environment environment,
                                     Authenticator authenticator,
                                     ResponseSigner signer,
                                     VirologyService service,
                                     VirologyWebsiteConfig websiteConfig,
                                     Duration delayDuration) {
        handler = withSignedResponses(
            environment,
            authenticator,
            signer,
            routes(
                path(Method.POST, "/virology-test/home-kit/order", (r) ->
                    handleVirologyOrder(service, websiteConfig, VirologyRequestType.ORDER)),
                path(Method.POST, "/virology-test/home-kit/register", (r) ->
                    handleVirologyOrder(service, websiteConfig, VirologyRequestType.REGISTER)),
                path(Method.POST, "/virology-test/results", (r) ->
                    deserializeMaybe(r.getBody(), VirologyLookupRequest.class)
                        .map(it -> service.virologyLookupFor(it).toHttpResponse())
                        .orElse(HttpResponses.unprocessableEntity())),
                path(Method.POST, "/virology-test/cta-exchange", (r) ->
                    throttlingResponse(
                        delayDuration,
                        () -> deserializeMaybeLogInfo(r.getBody(), CtaExchangeRequest.class)
                            .map(it -> service.exchangeCtaToken(it).toHttpResponse())
                            .orElseGet(HttpResponses::badRequest)
                    )
                ),
                path(Method.POST, "/virology-test/health", (r) ->
                    HttpResponses.ok()
                )
            )
        );
    }

    private APIGatewayProxyResponseEvent handleVirologyOrder(VirologyService service,
                                                             VirologyWebsiteConfig websiteConfig,
                                                             VirologyRequestType order) {
        var response = service.handleTestOrderRequest(websiteConfig, order);
        logger.info(
            "Virology order created ctaToken: {}, testResultToken: {}",
            response.tokenParameterValue, response.testResultPollingToken
        );
        return HttpResponses.ok(Jackson.toJson(response));
    }

    private static VirologyService virologyService(Supplier<Instant> clock, Environment environment) {
        return new VirologyService(
            new VirologyPersistenceService(
                AmazonDynamoDBClientBuilder.defaultClient(),
                virologyConfig(environment)
            ),
            new TokensGenerator(),
            clock
        );
    }

    private static final Environment.EnvironmentKey<String> TEST_ORDERS_TABLE = string("test_orders_table");
    private static final Environment.EnvironmentKey<String> TEST_RESULTS_TABLE = string("test_results_table");
    private static final Environment.EnvironmentKey<String> TEST_ORDERS_INDEX = string("test_orders_index");
    
    private static VirologyConfig virologyConfig(Environment environment) {
        return new VirologyConfig(
            environment.access.required(TEST_ORDERS_TABLE),
            environment.access.required(TEST_RESULTS_TABLE),
            environment.access.required(EnvironmentKeys.SUBMISSIONS_TOKENS_TABLE),
            environment.access.required(TEST_ORDERS_INDEX),
            VirologyConfig.MAX_TOKEN_PERSISTENCE_RETRY_COUNT
        );
    }

    private static final Environment.EnvironmentKey<String> ORDER_WEBSITE = string("order_website");
    private static final Environment.EnvironmentKey<String> REGISTER_WEBSITE = string("register_website");

    private static VirologyWebsiteConfig websiteConfig(Environment environment) {
        return new VirologyWebsiteConfig(
            environment.access.required(ORDER_WEBSITE),
            environment.access.required(REGISTER_WEBSITE)
        );
    }

    @Override
    public Routing.Handler handler() {
        return handler;
    }
}
