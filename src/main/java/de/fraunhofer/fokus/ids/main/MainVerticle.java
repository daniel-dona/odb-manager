package de.fraunhofer.fokus.ids.main;

import de.fraunhofer.fokus.ids.controller.BrokerMessageController;
import de.fraunhofer.fokus.ids.services.brokerMessageService.BrokerMessageServiceVerticle;
import de.fraunhofer.fokus.ids.services.databaseService.DatabaseServiceVerticle;
import de.fraunhofer.fokus.ids.services.dcatTransformerService.DCATTransformerServiceVerticle;
import de.fraunhofer.fokus.ids.utils.InitService;
import io.vertx.core.*;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import org.apache.http.entity.ContentType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MainVerticle extends AbstractVerticle {
    private Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class.getName());
    private Router router;
    private BrokerMessageController brokerMessageController;

    @Override
    public void start(Future<Void> startFuture) {
        this.router = Router.router(vertx);
        this.brokerMessageController = new BrokerMessageController(vertx);
        DeploymentOptions deploymentOptions = new DeploymentOptions();
        deploymentOptions.setWorker(true);

        Future<String> deployment = Future.succeededFuture();
        deployment.compose(id1 -> {
            Future<String> dcatTransformer = Future.future();
            vertx.deployVerticle(DCATTransformerServiceVerticle.class.getName(), deploymentOptions, dcatTransformer.completer());
            return dcatTransformer;
        }).compose(id2 -> {
            Future<String> brokerMessage = Future.future();
            vertx.deployVerticle(BrokerMessageServiceVerticle.class.getName(), deploymentOptions, brokerMessage.completer());
            return brokerMessage;
        }).compose(id3 -> {
            Future<String> databaseMessage = Future.future();
            vertx.deployVerticle(DatabaseServiceVerticle.class.getName(), deploymentOptions, databaseMessage.completer());
            return databaseMessage;
        }).setHandler(ar -> {
            if (ar.succeeded()) {

                Future<Void> initFuture = Future.future();
                new InitService(vertx).initDatabase(initFuture.completer());

                if (initFuture.succeeded()) {
                    router = Router.router(vertx);
                    createHttpServer(vertx);
                    startFuture.complete();
                } else {
                    startFuture.fail(initFuture.cause());
                }
            } else {
                startFuture.fail(ar.cause());
            }
        });
    }

    private void createHttpServer(Vertx vertx) {
        HttpServer server = vertx.createHttpServer();

        Set<String> allowedHeaders = new HashSet<>();
        allowedHeaders.add("x-requested-with");
        allowedHeaders.add("Access-Control-Allow-Origin");
        allowedHeaders.add("Access-Control-Allow-Credentials");
        allowedHeaders.add("origin");
        allowedHeaders.add("authorization");
        allowedHeaders.add("Content-Type");
        allowedHeaders.add("accept");
        allowedHeaders.add("Access-Control-Allow-Headers");
        allowedHeaders.add("Access-Control-Allow-Methods");
        allowedHeaders.add("X-PINGARUNER");

        Set<HttpMethod> allowedMethods = new HashSet<>();
        allowedMethods.add(HttpMethod.GET);
        allowedMethods.add(HttpMethod.POST);

        router.route().handler(CorsHandler.create("*").allowedHeaders(allowedHeaders).allowedMethods(allowedMethods));
        router.route().handler(BodyHandler.create());

        router.post("/data").handler(routingContext -> brokerMessageController.getData(routingContext.getBodyAsString(),
                reply -> reply(reply, routingContext.response())));
        router.route("/about").handler(routingContext -> brokerMessageController.about(reply -> reply(reply, routingContext.response())));
        LOGGER.info("Starting odb manager ");
        server.requestHandler(router).listen(8092);
        LOGGER.info("odb-manager deployed on port " + 8080);
    }

    private void reply(AsyncResult result, HttpServerResponse response) {
        if (result.succeeded() && result.result() != null) {
            String entity = result.result().toString();
            if (!response.headWritten()) {
                response.putHeader("content-type", ContentType.APPLICATION_JSON.toString());
                response.end(entity);
            }
        } else {
            response.setStatusCode(404).end();
        }
    }

    public static void main(String[] args) {
        String[] params = Arrays.copyOf(args, args.length + 1);
        params[params.length - 1] = MainVerticle.class.getName();
        Launcher.executeCommand("run", params);
    }
}
