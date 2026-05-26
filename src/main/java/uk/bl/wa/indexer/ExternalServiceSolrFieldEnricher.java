package uk.bl.wa.indexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
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
 * See  ExternalServiceSolrFieldEnricherIntegrationTest for easy way to test an existing enrichment service.
 */
public class ExternalServiceSolrFieldEnricher {
    private static Logger log = LoggerFactory.getLogger(ExternalServiceSolrFieldEnricher.class);
    private String serverUrl;
    private HashMap<String,String> solrFields2ToJsonAttributes;
    private HashMap<String,String> jsonAttributes2SolrFields;

    HttpClient httpClient = null;
    
    public ExternalServiceSolrFieldEnricher(String serverUrl, List<String> solrFields2ToJsonAttributesList, List<String>jsonAttributes2SolrFieldsList) {                                       
        this.serverUrl=serverUrl;
        this.solrFields2ToJsonAttributes=getFieldMapping(solrFields2ToJsonAttributesList);
        this.jsonAttributes2SolrFields=getFieldMapping(jsonAttributes2SolrFieldsList);        
        httpClient = HttpClientBuilder.create().setConnectionTimeToLive(10,TimeUnit.SECONDS).build(); //10 second timeout
    
    }
          
    /*
     * Extract only the solr field defined in the mapping
     */
    protected HashMap<String,String> extractSolrFieldsFromResponse(HashMap<String,String> jsonReponse) throws Exception{          
    
        HashMap<String,String>  solrFields= new HashMap<String,String>();
        for (String jsonKey: jsonReponse.keySet()) {
            String solrKey=jsonAttributes2SolrFields.get(jsonKey);
            if (solrKey != null) { //This is one of the values to index
             solrFields.put(solrKey, jsonReponse.get(jsonKey));                
            }                            
        }        
        return  solrFields;
    }

    /*
     * Map values in service response to solr field names and values
     */    
    public HashMap<String,String> getSolrEnrichmentFields(HashMap<String,String> jsonRequestParameters) throws Exception{          
    System.out.println("json map input:"+jsonRequestParameters);
        String json=generateJsonObject(jsonRequestParameters);               

        String jsonResponse=callService( json);
        HashMap<String, String> jsonFromService = parseJsonMapToJavaMap(jsonResponse);        
        HashMap<String, String> solrFields = extractSolrFieldsFromResponse(jsonFromService);
        return solrFields;

        
    }
    
    
    protected String callService(String json) throws Exception{       
        log.debug("Calling external service with JSON:"+json);
        HttpPost post = new HttpPost(serverUrl);
        StringEntity postingString = new StringEntity(json);
        post.setEntity(postingString);               
        post.setHeader("Content-type", "application/json");
        post.setHeader("Accept", "application/json");
        HttpResponse  response = httpClient.execute(post); 
        int statusCode= response.getStatusLine().getStatusCode();
        if (statusCode != 200) {
            log.warn("Not http 200 status from calling enrich service. Statuscode="+statusCode +" Json:"+json);
            return null;
        }
        String responseStr = new BasicResponseHandler().handleResponse(response);   
        log.debug("External service JSON:"+responseStr);
        return responseStr;       
    }
    
    protected String generateJsonObject(HashMap<String,String> parameters) {        
        JSONObject json=new JSONObject();
        for (String key:parameters.keySet()) {            
             String value=parameters.get(key);
              boolean numeric=StringUtils.isNumeric(value);
             if (numeric) {
                json.put(key, Integer.valueOf(value));
             }
             else{
                 json.put(key, value);
             }
        }                
        return json.toString();                             
    }
    
    @SuppressWarnings("unchecked")
    protected HashMap<String, String> parseJsonMapToJavaMap( String jsonString) {                
        HashMap<String, String> jsonKeyValueMap = new HashMap<String, String>(); 
        JSONObject json=new JSONObject(jsonString);
        Set<String> attributes = (Set<String>) json.keySet();
        for (String key:attributes) {
           Object object = json.get((String) key);                                                              
           jsonKeyValueMap.put(key, (String) object.toString());            
        }
        return jsonKeyValueMap;        
    }       

    /*
     * Map strings to map: 'source_file_path -> file_path'
     * Will be map element with (source_file_path, file_path) 
     */
    protected static HashMap<String,String> getFieldMapping( List<String> propertyMappingString){    
        HashMap<String,String> fieldMapping= new HashMap<String,String>();
        //Parse into map
        for (String field : propertyMappingString) {                                
            String[] keyVal=field.split("->");                 
            fieldMapping.put(keyVal[0].trim(),keyVal[1].trim());                                   
        }                 
        return fieldMapping;        
    }
    
    public HashMap<String,String> getSolrFields2JsonAttributes(){              
        return solrFields2ToJsonAttributes;
    }

}
