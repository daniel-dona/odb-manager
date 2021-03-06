package de.fraunhofer.fokus.ids.services.dcatTransformerService;

import de.fraunhofer.fokus.ids.utils.JsonLdContextResolver;
import de.fraunhofer.iais.eis.*;
import de.fraunhofer.iais.eis.ids.jsonld.Serializer;
import de.fraunhofer.iais.eis.util.TypedLiteral;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.vocabulary.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.UUID;

public class DCATTransformerServiceImpl implements DCATTransformerService {
    private final Logger LOGGER = LoggerFactory.getLogger(DCATTransformerServiceImpl.class.getName());
    private Serializer serializer = new Serializer();
    private JsonLdContextResolver jsonLdContextResolver;

    public DCATTransformerServiceImpl(JsonLdContextResolver jsonLdContextResolver, Handler<AsyncResult<DCATTransformerService>> readyHandler){
        this.jsonLdContextResolver = jsonLdContextResolver;
        readyHandler.handle(Future.succeededFuture(this));
    }

    @Override
    public DCATTransformerService transformCatalogue(String connectorJson,String issued, Handler<AsyncResult<String>> readyHandler) {
        Connector connector = null;
        try {
            connector = serializer.deserialize(connectorJson, Connector.class);
        } catch(Exception e){
            LOGGER.error(e);
            readyHandler.handle(Future.failedFuture(e));
        }
        Model model = setPrefixes(ModelFactory.createDefaultModel());
        org.apache.jena.rdf.model.Resource catalogue = model.createResource(connector.getId().toString())
                .addProperty(RDF.type, DCAT.Catalog)
                .addLiteral(DCTerms.type, "dcat-ap")
                .addProperty(DCTerms.language, model.createProperty("http://publications.europa.eu/resource/authority/language/ENG"));

        org.apache.jena.rdf.model.Resource publisher = model.createResource("http://ids.fokus.fraunhofer.de/publisher/"+UUID.randomUUID().toString());

        if (connector.getMaintainer()!=null) {
            publisher.addProperty(RDF.type, FOAF.Agent)
                    .addLiteral(FOAF.name, connector.getMaintainer().toString());
        }
        if (connector.getPhysicalLocation()!=null) {
            catalogue.addProperty(DCTerms.spatial, model.createResource(connector.getPhysicalLocation().getId().toString()));
        }

        catalogue.addProperty(DCTerms.publisher, publisher);

        addTypedLiterals(catalogue, connector.getTitle(), DCTerms.title, model);
        addTypedLiterals(catalogue, connector.getDescription(), DCTerms.description, model);
        addDateLiterals(catalogue,issued,model);
        try(ByteArrayOutputStream baos = new ByteArrayOutputStream()){
            model.write(baos, "TTL");
            readyHandler.handle(Future.succeededFuture(baos.toString()));
        } catch (IOException e) {
            LOGGER.error(e);
            readyHandler.handle(Future.failedFuture(e));
        }

        return this;
    }

    @Override
    public DCATTransformerService transformDataset(String datasetJson, String issued, Handler<AsyncResult<String>> readyHandler) {
        Resource dataasset = null;
        try {
            dataasset = serializer.deserialize(datasetJson, Resource.class);
        } catch(Exception e){
            LOGGER.error(e);
            readyHandler.handle(Future.failedFuture(e));
        }

        Model model = setPrefixes(ModelFactory.createDefaultModel());

        org.apache.jena.rdf.model.Resource dataset = model.createResource(dataasset.getId().toString())
                .addProperty(RDF.type, DCAT.Dataset);

        if (dataasset.getPublisher() != null ){
            checkNull(dataasset.getPublisher(),DCTerms.publisher,dataset);
        }

        checkNull(dataasset.getStandardLicense(),DCTerms.license,dataset);
        checkNull(dataasset.getVersion(),DCTerms.hasVersion,dataset);
        addTypedLiterals(dataset, dataasset.getKeyword(),DCAT.keyword, model);
        addDateLiterals(dataset,issued,model);
        if (dataasset.getTheme()!=null){
            for (URI uri:dataasset.getTheme()){
                checkNull(uri,DCAT.theme,dataset);
            }
        }

        for (Endpoint endpoint:dataasset.getResourceEndpoint()){
            String string = endpoint.getEndpointHost().getId()+endpoint.getPath();
            dataset.addProperty(DCAT.endpointURL,string);
        }

        if(dataasset.getLanguage() != null) {
            for (Language language : dataasset.getLanguage()) {
                dataset.addLiteral(DCTerms.language, language.toString());
            }
        }
        addTypedLiterals(dataset, dataasset.getTitle(), DCTerms.title, model);
        addTypedLiterals(dataset, dataasset.getDescription(), DCTerms.description, model);

        StaticEndpoint endpoint = (StaticEndpoint) dataasset.getResourceEndpoint().get(0);

        String accessUrl = endpoint.getEndpointHost().getAccessUrl()+endpoint.getPath()+((StaticEndpoint)endpoint).getEndpointArtifact().getFileName();
        String id = "http://example.org/"+ UUID.randomUUID().toString();
        org.apache.jena.rdf.model.Resource distribution = model.createResource(id)
                    .addProperty(RDF.type, DCAT.Distribution)
                    .addProperty(DCAT.accessURL, accessUrl)
                    .addProperty(DCTerms.title,"Distribution-"+endpoint.getEndpointArtifact().getFileName());
        if(dataasset.getCustomLicense() != null){
            distribution.addProperty(DCTerms.license, dataasset.getCustomLicense().toString());
        }

        dataset.addProperty(DCAT.distribution, distribution);

        try(ByteArrayOutputStream baos = new ByteArrayOutputStream()){
            model.write(baos, "TTL");
            readyHandler.handle(Future.succeededFuture(baos.toString()));
        } catch (IOException e) {
            LOGGER.error(e);
            readyHandler.handle(Future.failedFuture(e));
        }

        return this;
    }

    @Override
    public DCATTransformerService transformJsonForVirtuoso(String json, Handler<AsyncResult<String>> readyHandler) {
        JsonObject connector = new JsonObject(json);
        jsonLdContextResolver.resolve( ac -> {
            if(ac.succeeded()){
                connector.put("@context", ac.result().getJsonObject("@context"));
                try{
                    readyHandler.handle(Future.succeededFuture(connector.toString()));
                } catch (Exception e) {
                    LOGGER.error(e);
                    readyHandler.handle(Future.failedFuture(e));
                }
            }
            else {
                readyHandler.handle(Future.failedFuture(ac.cause()));
            }
        });
        return this;
    }

    private void addDateLiterals(org.apache.jena.rdf.model.Resource resource,String issued, Model model){
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss");
        resource.addLiteral(DCTerms.modified,model.createTypedLiteral(sdf.format(date),"xsd:dateTime"));
        if (issued!=null) {
            try {
                date = sdf.parse(issued);
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }
            resource.addLiteral(DCTerms.issued,model.createTypedLiteral(sdf.format(date),"xsd:dateTime"));
    }

    private void addTypedLiterals(org.apache.jena.rdf.model.Resource resource, ArrayList<? extends TypedLiteral> list, Property relation, Model model){
        if(list != null) {
            for (TypedLiteral literal : list) {
                String lang = "en";
                if(literal.getLanguage()!=null){
                    lang = literal.getLanguage();
                }
                resource.addLiteral(relation,model.createLiteral(literal.getValue(), lang));
            }
        }
    }

    private Model setPrefixes(Model model) {
        return model.setNsPrefix("dcat", DCAT.NS)
                .setNsPrefix("dct", DCTerms.NS)
                .setNsPrefix("foaf", FOAF.NS)
                .setNsPrefix("locn","http://www.w3.org/ns/locn#")
                .setNsPrefix("owl", OWL.NS)
                .setNsPrefix("rdf", RDF.uri)
                .setNsPrefix("rdfs", RDFS.uri)
                .setNsPrefix("schema","http://schema.org/")
                .setNsPrefix("skos", SKOS.uri)
                .setNsPrefix("time","http://www.w3.org/2006/time")
                .setNsPrefix("vcard", VCARD.uri)
                .setNsPrefix("xml","http://www.w3.org/XML/1998/namespace")
                .setNsPrefix("xsd", XSD.NS);
    }

    private void checkNull(Object object, Property property,org.apache.jena.rdf.model.Resource resource ){
        if (object!=null){
            resource.addProperty(property, String.valueOf(object));
        }
    }

}
