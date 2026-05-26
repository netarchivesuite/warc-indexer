package uk.bl.wa.indexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ExternalServiceSolrFieldEnricherIntegrationTest {
    

    /**
     *  Convenience main method to manual call and test an external webservice. Easy to run from IDE. 
     */
    public static void main(String[] args) throws Exception{    
        //String url="http://localhost:8000/warc-safe/test_nsfw"; //If no clamAV is installed
        String url="http://localhost:8000/test_all"; //Must be running

        //Define field mapping from solr fields to service
        List<String> solrFields2ToJsonAttributes=  new ArrayList<String>(); // This is the fields that will be extracted from the response(if present)
        solrFields2ToJsonAttributes.add("source_file_path  -> file_path");
        solrFields2ToJsonAttributes.add("source_file_offset -> offset");
        
        //Define field mapping from service to solr fields
        List<String> jsonAttributes2SolrFields=  new ArrayList<String>();; // This is the fields that will be extracted from the response(if present)        
        jsonAttributes2SolrFields.add("nsfw_score -> nsfw_probability");
        jsonAttributes2SolrFields.add("is_nsfw -> is_nsfw"); //Still not implemented in service
        jsonAttributes2SolrFields.add("is_virus  ->  is_virus"); //Still not implemented in service
        jsonAttributes2SolrFields.add("av_details  -> virus_description");
                       
        ExternalServiceSolrFieldEnricher enrichService= new ExternalServiceSolrFieldEnricher(url,solrFields2ToJsonAttributes,jsonAttributes2SolrFields);

        //Test single warc entry
        HashMap<String,String> jsonRequestParameters = new HashMap<String,String>();
        jsonRequestParameters.put("file_path","/home/teg/solrwayback/warcs_webkid_filtered/DENMARK-EXTRACTED-2000-part-00000003.warc");
        jsonRequestParameters.put("offset","682189");
        
        String json= enrichService.generateJsonObject(jsonRequestParameters);               
        System.out.println("POST json to service:"+json);
        String jsonResponse = enrichService.callService( json);
        System.out.println("Service response:"+jsonResponse);
        HashMap<String, String> jsonFromService = enrichService.parseJsonMapToJavaMap(jsonResponse);        
        System.out.println("Json fields in response:"+jsonFromService);
        HashMap<String, String> solrFields = enrichService.extractSolrFieldsFromResponse(jsonFromService);
        System.out.println("solr fields:"+solrFields);
    }

}
