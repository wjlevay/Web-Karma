package edu.isi.karma.web.services.publish.es;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.json.JSONException;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.StmtIterator;

import edu.isi.karma.config.ModelingConfiguration;
import edu.isi.karma.controller.update.UpdateContainer;
import edu.isi.karma.kr2rml.ContextIdentifier;
import edu.isi.karma.kr2rml.mapping.R2RMLMappingIdentifier;
import edu.isi.karma.kr2rml.planning.UserSpecifiedRootStrategy;
import edu.isi.karma.kr2rml.writer.JSONKR2RMLRDFWriter;
import edu.isi.karma.kr2rml.writer.KR2RMLRDFWriter;
import edu.isi.karma.metadata.KarmaMetadataManager;
import edu.isi.karma.metadata.PythonTransformationMetadata;
import edu.isi.karma.metadata.UserConfigMetadata;
import edu.isi.karma.metadata.UserPreferencesMetadata;
import edu.isi.karma.modeling.Uris;
import edu.isi.karma.modeling.semantictypes.SemanticTypeUtil;
import edu.isi.karma.rdf.GenericRDFGenerator;
import edu.isi.karma.rdf.RDFGeneratorRequest;
import edu.isi.karma.webserver.KarmaException;

@Path("/")
public class ElasticSearchPublishServlet extends Application {

	private static Logger logger = LoggerFactory
			.getLogger(ElasticSearchPublishServlet.class);

	private static final int retry = 10;
	private int bulksize = 100;
	private int sleepTime = 100;
	private ServletContext context;
	
	public ElasticSearchPublishServlet(@Context ServletContext context) {
		this.context = context;
		String bulksize = context.getInitParameter("ESBulkSize");
		if(bulksize != null)
			this.bulksize = Integer.parseInt(bulksize);
		String sleep = context.getInitParameter("ESUploadInterval");
		if(sleep != null)
			this.sleepTime = Integer.parseInt(sleep);
	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Path("/data")
	public String publishFromData(MultivaluedMap<String, String> formParams) {
		try {
			logger.info("Path - es/json . Generate jsonld and publish to ES");
			ElasticSearchConfig esConfig = ElasticSearchConfig.parse(context, formParams);
			R2RMLConfig r2rmlConfig = R2RMLConfig.parse(context, formParams);
			String jsonld = generateJSONLD(r2rmlConfig);
			if(jsonld != null)
				return publishES(jsonld, esConfig);
		} catch (Exception e) {
			logger.error("Error generating JSON", e);
			return "Exception: " + e.getMessage();
		}
		return null;
	}
	
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Path("/jsonld")
	public String publishFromJsonLD(MultivaluedMap<String, String> formParams) {
		try {
			logger.info("Path - es/jsonld . Publish JSONLD to ES");
			ElasticSearchConfig esConfig = ElasticSearchConfig.parse(context, formParams);
			R2RMLConfig r2rmlConfig = R2RMLConfig.parse(context, formParams);
			String jsonld = IOUtils.toString(r2rmlConfig.getInput());
			if(jsonld != null)
				return publishES(jsonld, esConfig);
		} catch (Exception e) {
			logger.error("Error generating JSON", e);
			return "Exception: " + e.getMessage();
		}
		return null;
	}

	
	private String publishES(String jsonld, ElasticSearchConfig esConfig) throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
		
		CloseableHttpClient httpClient = getHttpClient(esConfig);
		HttpPost httpPost = getHttpPost(esConfig);
		
		String bulkFormat = null;
		StringBuilder sb = new StringBuilder();
		
//		System.out.println("GOt JSONLD:");
//		System.out.println(jsonld);
		
		JSON json = JSONSerializer.toJSON(jsonld);
		JSONArray jsonArray = null;
		if(json instanceof JSONObject) {
			jsonArray = new JSONArray();
			jsonArray.add(json);
		} else {
			jsonArray = (JSONArray)json;
		}
		
		long counter = 0;
		Exception postException = null;
		String index = esConfig.getIndex();
		String type = esConfig.getType();
		
		for(int k=0; k<jsonArray.size(); k++) {
			JSONObject jObj = jsonArray.getJSONObject(k);
		
			
			String id = null;
			
			if(jObj.containsKey("uri"))
			{
				id = jObj.getString("uri");
			}
			
			if(id != null)
			{
				bulkFormat = "{\"index\":{\"_index\":\"" + index+ "\",\"_type\":\""+ type +"\",\"_id\":\""+id+"\"}}";
			}
			else
			{
				bulkFormat = "{\"index\":{\"_index\":\"" + index+ "\",\"_type\":\""+ type +"\"}}";
			}
			sb.append(bulkFormat);
			sb.append(System.getProperty("line.separator"));
			sb.append(jObj.toString());
			sb.append(System.getProperty("line.separator"));
			counter++;
			if (counter % bulksize == 0) {
				int i = 0;
				Exception ex = null;
				while (i < retry) {
					try {
						StringEntity entity = new StringEntity(sb.toString(),"UTF-8");
						entity.setContentType("application/json");
						httpPost.setEntity(entity);
						httpClient.execute(httpPost);
						httpClient.close();
						System.out.println(counter + " processed");
						break;
					}catch(Exception e) {
						ex = e;
						i++;
					}
				}
				if (i > 0) {
					System.out.println("Exception occurred!");
					ex.printStackTrace();
					postException = ex;
					break;
				}
				httpClient = getHttpClient(esConfig);
				httpPost = getHttpPost(esConfig);
				sb = new StringBuilder();
				try {
					Thread.sleep(sleepTime);
				} catch (InterruptedException e) {
				}
			}
		}
		try {
			StringEntity entity = new StringEntity(sb.toString(),"UTF-8");
			entity.setContentType("application/json");
			httpPost.setEntity(entity);
			httpClient.execute(httpPost);
			httpClient.close();
		} catch(Exception e) {
			e.printStackTrace();
			postException = e;
		}
		
		if (postException != null) {
			System.out.println("Exception occurred!");
			return "{\"result\": {\"code\": \"0\", \"message\": \"" + postException.getMessage() + "\"}}";
		}
		return "{\"result\": {\"code\": \"1\", \"message\": \"success\"}}";
	}
	
	private HttpPost getHttpPost(ElasticSearchConfig esConfig) {
		return new HttpPost(esConfig.getProtocol()+"://" + esConfig.getHostname() + 
				":" + esConfig.getPort() + "/" + esConfig.getIndex() + "/_bulk");
	}
	
	private CloseableHttpClient getHttpClient(ElasticSearchConfig esConfig) throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
		if(esConfig.getProtocol().equalsIgnoreCase("https")) {
			SSLContextBuilder builder = new SSLContextBuilder();
			builder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
			SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(builder.build());
			return HttpClients.custom().setSSLSocketFactory(sslsf).build();
		} else if(esConfig.getProtocol().equalsIgnoreCase("http"))
			return HttpClients.createDefault();
		return null;
		
	}
	
	private String generateJSONLD(R2RMLConfig config) throws JSONException, MalformedURLException, KarmaException, IOException{

		InputStream is = config.getInput();
		
		if(is != null) {
			
			URL contextLocation = config.getContextUrl();

	        URLConnection contextConnection = contextLocation.openConnection();
			GenericRDFGenerator rdfGen = new GenericRDFGenerator(null);

			// Add the models in;
			R2RMLMappingIdentifier modelIdentifier = new R2RMLMappingIdentifier(
					"generic-model", config.getR2rmlUrl()); 
			rdfGen.addModel(modelIdentifier);
			Model model = rdfGen.getModelParser("generic-model").getModel();
			
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			
			JSONTokener token = new JSONTokener(contextConnection.getInputStream());

			ContextIdentifier contextId = new ContextIdentifier("generic-context", contextLocation);
			JSONKR2RMLRDFWriter writer = new JSONKR2RMLRDFWriter(pw);
			writer.setGlobalContext(new org.json.JSONObject(token), contextId); 
			RDFGeneratorRequest request = generateRDFRequest("generic-model", model, "Karma-Web-Services", is, config, writer);
			rdfGen.generateRDF(request);
			String rdf = sw.toString();
			return rdf;
		}
		
		return null;
	}
	
	private RDFGeneratorRequest generateRDFRequest(String modelName, Model model, String sourceName, InputStream is, R2RMLConfig config, KR2RMLRDFWriter writer) {
		RDFGeneratorRequest request = new RDFGeneratorRequest(modelName, sourceName);
		request.addWriter(writer);
		request.setInputStream(is);
		
		request.setDataType(config.getContentType());
		request.setAddProvenance(false);
		
		request.setMaxNumLines(config.getMaxNumLines());
		request.setEncoding(config.getEncoding());
		request.setDelimiter(config.getColumnDelimiter());
		request.setTextQualifier(config.getTextQualifier());
		request.setDataStartIndex(config.getDataStartIndex());
		request.setHeaderStartIndex(config.getHeaderStartIndex());
		request.setWorksheetIndex(config.getWorksheetIndex());
		
		String rootTripleMap = config.getContextRoot();
		if(rootTripleMap != null && !rootTripleMap.isEmpty()) {
			StmtIterator itr = model.listStatements(null, model.getProperty(Uris.KM_NODE_ID_URI), rootTripleMap);
			Resource subject = null;
			while (itr.hasNext()) {
				subject = itr.next().getSubject();
			}
			if (subject != null) {
				itr = model.listStatements(null, model.getProperty(Uris.RR_SUBJECTMAP_URI), subject);
				while (itr.hasNext()) {
					rootTripleMap = itr.next().getSubject().toString();
				}
			}
		}
		
		request.setStrategy(new UserSpecifiedRootStrategy(rootTripleMap));
		return request;
	}

	static {
		try {
			initialization();
		} catch (KarmaException ke) {
			logger.error("KarmaException: " + ke.getMessage());
		}
	}


	private static void initialization() throws KarmaException {
		UpdateContainer uc = new UpdateContainer();
		KarmaMetadataManager userMetadataManager = new KarmaMetadataManager();
		userMetadataManager.register(new UserPreferencesMetadata(), uc);
		userMetadataManager.register(new UserConfigMetadata(), uc);
		userMetadataManager.register(new PythonTransformationMetadata(), uc);

		SemanticTypeUtil.setSemanticTypeTrainingStatus(false);

		ModelingConfiguration.setLearnerEnabled(false); // disable automatic													// learning
	}

}
