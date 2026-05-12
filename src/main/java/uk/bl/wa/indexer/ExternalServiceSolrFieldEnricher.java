package uk.bl.wa.indexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class can enrich solr fields from an external service. <br>
 * <br>
 * In the config3.xml see the property 'enrich' and it can be enabled.  <br>
 * The service will be called from each record in the WARC-file and POST data will be a JSON object  <br> 
 * with key/value from the fully populated Solr document. Define the list of Solr key/value pairs in the list property: solrFieldsInRequest  <br>
 * <br>
 * The service must return a JSON object. Key/values will overwrite the Solr fields if they are present in the response. <br>
 * Will only overwrite Solr records that are define the list property: jsonFieldsInResponse <br>
 * <br>
 * This class has a main method for easy integration test to the external service.
 */
public class ExternalServiceSolrFieldEnricher {
    private static Logger log = LoggerFactory.getLogger(ExternalServiceSolrFieldEnricher.class);
    private String serverUrl;
    private HashSet<String> fieldsToExtract;
    HttpClient httpClient = null;
    
    public ExternalServiceSolrFieldEnricher(String serverUrl,  List<String> fieldsToExtract) {                
        this.serverUrl=serverUrl;
        this.fieldsToExtract=new HashSet<String>(fieldsToExtract);
        httpClient = HttpClientBuilder.create().build();
    }
    
    /**
     *  Convenience main method to manual call and test an external webservice. Easy to run from IDE. 
     */
    public static void main(String[] args) throws Exception{    
        //String url="http://localhost:8000/warc-safe/new_method";
        String url="http://localhost:8000/";
        List<String>fieldsToExtract=  new ArrayList<String>(); // This is the fields that will be extracted from the response(ir present)        
        fieldsToExtract.add("nsfw_probability");
        fieldsToExtract.add("is_nsfw");
        fieldsToExtract.add("is_virus");
        fieldsToExtract.add("virus_description");
        
        ExternalServiceSolrFieldEnricher enrichService= new ExternalServiceSolrFieldEnricher(url,fieldsToExtract);

        HashMap<String,String> jsonRequestParameters = new HashMap<String,String>();
        jsonRequestParameters.put("file_path","/home/xxx/test.warc.gz");
        jsonRequestParameters.put("offset","1234565");
        
        String json= enrichService.generateJsonObject(jsonRequestParameters);               
        System.out.println("POST json to service:"+json);
        String jsonResponse = enrichService.callService( json);
        System.out.println("Service response:"+json);
        
        HashMap<String, String> solrfields = enrichService.parseJsonMapToJavaMap(jsonResponse);        
        System.out.println("Extraced solr fields and value:"+solrfields);        
    }
    
    
    public HashMap<String,String> extractEnrichFields (HashMap<String,String> jsonRequestParameters) throws Exception{          
        String json= generateJsonObject(jsonRequestParameters);                
        String jsonResponse = callService(json);              
        if (jsonResponse == null) {
            return new HashMap<String, String>();
        }
        HashMap<String, String> solrfields = parseJsonMapToJavaMap(jsonResponse);       
        return solrfields;                  
    }
    
    private String callService(String json) throws Exception{       
        HttpPost     post          = new HttpPost(serverUrl);
        StringEntity postingString = new StringEntity(json);
        post.setEntity(postingString);               
        post.setHeader("Content-type", "application/json");
        post.setHeader("Accept", "application/json");
        HttpResponse  response = httpClient.execute(post); 
        int statusCode= response.getStatusLine().getStatusCode();
        if (statusCode != 200) {
            log.warn("Not http 200 status from calling enrich service with json:"+json);
            return null;
        }
        String responseStr = new BasicResponseHandler().handleResponse(response);   
        return responseStr;       
    }
    
    private String generateJsonObject(HashMap<String,String> parameters) {        
        JSONObject json=new JSONObject();
        for (String key:parameters.keySet()) {            
           json.put(key, parameters.get(key));
        }                
        return json.toString();                             
    }
    
    @SuppressWarnings("unchecked")
    private HashMap<String, String> parseJsonMapToJavaMap( String jsonString) {                
        HashMap<String, String> extractedSolrFieldsAndValues = new HashMap<String, String>(); 
        JSONObject json=new JSONObject(jsonString);
        Set<String> attributes = (Set<String>) json.keySet();
        for (String key:attributes) {
            Object object = json.get((String) key);
            if (object instanceof String ) { //Ignore arrays ( multivalue)                           
                if (fieldsToExtract.contains(key)) {
                    String value=(String) object;
                    extractedSolrFieldsAndValues.put(key, value);                    
                    System.out.println("Added Key:"+key +" value:"+value);
                }                
            }            
        }
        return extractedSolrFieldsAndValues;        
    }       
}
