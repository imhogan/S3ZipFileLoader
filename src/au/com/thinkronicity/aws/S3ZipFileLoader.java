package au.com.thinkronicity.aws;

/*
import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;
*/
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;

public class S3ZipFileLoader implements RequestHandler<Map<String, Object>, Object> {

    private boolean debug = false;

    private static final String version = "1.2.0CE";
	/*
	 * (non-Javadoc)
	 * @see com.amazonaws.services.lambda.runtime.RequestHandler#handleRequest(java.lang.Object, com.amazonaws.services.lambda.runtime.Context)
	 * Based on code from:
	 *      * https://github.com/Craftware/aws-lambda-unzip/blob/master/src/main/java/kornell/S3EventProcessorUnzip.java#L52 
	 * and
	 *      * http://stackoverflow.com/questions/32811947/how-do-we-access-and-respond-to-cloudformation-custom-resources-using-an-aws-lam
	 *      
	 *      
	 *      Cloudformation usage:
	 *      
  "MyCustomResource": {
  "Type" : "Custom::String",
  "Version" : "1.0",
  "Properties": {
    "ServiceToken": "arn:aws:lambda:us-east-1:xxxxxxx:function:MyCloudFormationResponderLambdaFunction",
    "SourceBucket": "source bucket name - should be in the same region the stack is being built in",
    "SourceKeys": ["key1.zip", "key2.zip"],
    "TargetBucket": "target bucket name - should be in the same region the stack is being built in",
    "PublicRead": "keys matching this RegExp will be set to PublicRead, eg ^(images/|xsl/SurveyReports|xsl/f/).*$",
    "Debug": "true if debug logging is required"
  }
}
	 */
    @Override
    public Object handleRequest(Map<String, Object> input, Context context) {
        context.getLogger().log(Timestamp()+": " + this.getClass().getName() + ", version "  + version);
        context.getLogger().log(Timestamp()+": Input: " + input);

        String responseURL = (String)input.get("ResponseURL");
        context.getLogger().log(Timestamp()+": ResponseURLInput: " + responseURL);
        context.getLogger().log(Timestamp()+": StackId Input: " + input.get("StackId"));
        context.getLogger().log(Timestamp()+": RequestId Input: " + input.get("RequestId"));
        context.getLogger().log(Timestamp()+": LogicalResourceId Context: " + input.get("LogicalResourceId"));
        context.getLogger().log(Timestamp()+": Physical Context: " + context.getLogStreamName());
        String srcZipsList = "";
        String operation = (String) input.get("RequestType");
        String deletionPolicy = (String) input.getOrDefault("DeletionPolicy", "Delete");
        String operationStatus = "SUCCESS";
        String failedReason = "";
        
        if (!operation.equals("Delete") || deletionPolicy.equals("Delete")) {
	        try {
		        @SuppressWarnings("unchecked")
		        Map<String,Object> resourceProps = (Map<String,Object>)input.get("ResourceProperties");
	
	            byte[] buffer = new byte[1024];
	            
	            String srcBucket = (String) resourceProps.getOrDefault("SourceBucket", "");
	            String targetBucket = (String) resourceProps.getOrDefault("TargetBucket", "");
	            String publicReadMask = (String) resourceProps.getOrDefault("PublicRead", "");
	            debug = resourceProps.get("Debug") != null && resourceProps.getOrDefault("Debug", "false").toString().equals("true");
	            AmazonS3 s3Client = new AmazonS3Client();
	            
	            if (resourceProps.containsKey("SourceKeys")) {
			        @SuppressWarnings("unchecked")
			        List<String> mySrcKeys = (ArrayList<String>)resourceProps.get("SourceKeys");
			        
			        for(String srcKey : mySrcKeys){
		            
			        	if (debug) {
			                context.getLogger().log(Timestamp()+": Processing Zip file: s3://" + srcBucket + "/" + srcKey);
			        	}
			        	S3Object s3Object = s3Client.getObject(new GetObjectRequest(srcBucket, srcKey));
			            ZipInputStream zis = new ZipInputStream(s3Object.getObjectContent());
			            ZipEntry entry = zis.getNextEntry();
			
			
			            while(entry != null) {
			                String fileName = entry.getName();
			                if (operation.equals("Delete")) {
					        	if (debug) {
					        		context.getLogger().log(Timestamp()+": Deleting  s3://" + targetBucket + "/" + fileName);
					        	}
				                s3Client.deleteObject(targetBucket, fileName);
			                }
			                else {
					        	if (debug) {
					        		context.getLogger().log(Timestamp()+": Extracting " + fileName + ", compressed: " + entry.getCompressedSize() + " bytes, extracted: " + entry.getSize() + " bytes");
					        	}
				                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				                int len;
								while ((len = zis.read(buffer)) > 0) {
				                    outputStream.write(buffer, 0, len);
				                }
				                InputStream is = new ByteArrayInputStream(outputStream.toByteArray());
				                ObjectMetadata meta = new ObjectMetadata();
				                meta.setContentLength(outputStream.size());
				                s3Client.putObject(targetBucket, fileName, is, meta);
				                if (fileName.matches(publicReadMask)) {
						        	if (debug) {
						        		context.getLogger().log(Timestamp()+": Setting public-read permission on s3://" + targetBucket + "/" + fileName);
						        	}
				                	s3Client.setObjectAcl(targetBucket, fileName, CannedAccessControlList.PublicRead);
				                }
				                /* TODO: Could take a list of file (mask, permissions) pairs and set permissions accordingly.
				                String fileAcl;
								s3Client.setObjectAcl(targetBucket, fileName, CannedAccessControlList.valueOf(fileAcl));
								*/
				                is.close();
				                outputStream.close();
			                }
			                entry = zis.getNextEntry();
			            }
			            zis.closeEntry();
			            zis.close();
			            
			            srcZipsList += (srcZipsList.isEmpty()?"":",")+"s3://"+srcBucket+"/"+srcKey;
			        }
	            }
	        } catch (Exception e) {
	            e.printStackTrace();
	            operationStatus = "FAILED";
	            failedReason = e.getMessage();
	        }        
        }
        
        context.getLogger().log(Timestamp()+": operationStatus: " + operationStatus);
        context.getLogger().log(Timestamp()+": failedReason: " + failedReason);

        try {
            URL url = new URL(responseURL);
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");
            OutputStreamWriter out = new OutputStreamWriter(connection.getOutputStream());
          /*
            JSONObject cloudFormationJsonResponse = new JSONObject();
            try {
                cloudFormationJsonResponse.put("Status", operationStatus);
                if (!failedReason.isEmpty()) {
                    cloudFormationJsonResponse.put("Reason", failedReason);
                }
                cloudFormationJsonResponse.put("PhysicalResourceId", context.getLogStreamName());
                cloudFormationJsonResponse.put("StackId", input.get("StackId"));
                cloudFormationJsonResponse.put("RequestId", input.get("RequestId"));
                cloudFormationJsonResponse.put("LogicalResourceId", input.get("LogicalResourceId"));
                cloudFormationJsonResponse.put("Data", new JSONObject().put("_self_version", version).put("SourceZips", srcZipsList));
            } catch (JSONException e) {
                e.printStackTrace();
            }
            out.write(cloudFormationJsonResponse.toString());
            
            
                    Map<String,String> map = new HashMap<>();
        map.put("key1","value1");
        map.put("key2","value2");

        String mapAsJson = new ObjectMapper().writeValueAsString(map);
        
            */
             Map<String,Object> cloudFormationJsonResponse = new HashMap<String, Object>();
            try {
                cloudFormationJsonResponse.put("Status", operationStatus);
                if (!failedReason.isEmpty()) {
                    cloudFormationJsonResponse.put("Reason", failedReason);
                }
                cloudFormationJsonResponse.put("PhysicalResourceId", context.getLogStreamName());
                cloudFormationJsonResponse.put("StackId", input.get("StackId"));
                cloudFormationJsonResponse.put("RequestId", input.get("RequestId"));
                cloudFormationJsonResponse.put("LogicalResourceId", input.get("LogicalResourceId"));
                Map<String,String> dataMap = new HashMap<String, String>();
                dataMap.put("_self_version", version);
                dataMap.put("SourceZips", srcZipsList);
                cloudFormationJsonResponse.put("Data", dataMap);
            } catch (Exception e) {
                e.printStackTrace();
            }
            out.write(new ObjectMapper().writeValueAsString(cloudFormationJsonResponse));
          
            out.close();
            int responseCode = connection.getResponseCode();
            context.getLogger().log(Timestamp()+": Response Code: " + responseCode);
        } catch (Exception e) {
            e.printStackTrace();
        }        
        return null;
    }

	/**
	 * Create a timestamp for logging.
	 * @return
	 */
	public static String Timestamp() {

		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSSXXX");
       
        //to convert Date to String, use format method of SimpleDateFormat class.
        return dateFormat.format(new Date());		
	}
    
}
