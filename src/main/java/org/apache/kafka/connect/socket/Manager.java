package org.apache.kafka.connect.socket;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.kafka.connect.socket.batch.BulkProcessor;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import io.netty.buffer.ByteBuf;
import io.reactivex.netty.server.RxServer;

public final class Manager {
	private final static Logger log = LoggerFactory.getLogger(Manager.class);
    
    public static final ConcurrentLinkedQueue<byte[]> MESSAGES = new ConcurrentLinkedQueue<>();
    public final static Set<String> TOPICS = new HashSet<String>();
    /** All the ID/Key related mappings and domain names***/
    public final static Map<String,Set<String>> ID_MAP = new HashMap<>(); 
    /** Domain and Topic mappings- domain name will be the key while topic will be the value**/
    public final static Map<String,String> DOMAIN_TOPIC_MAP = new HashMap<>();
    /**application level configuration which reside in property file**/
    public final static Properties APP_CONFIGS = new Properties();
    private static JsonElement JSON_ELEMENT;
    
    private static final ThreadLocal<MessageDigest> MD_TL = new ThreadLocal<MessageDigest>() {
    	protected MessageDigest initialValue() {
    		MessageDigest MESSAGE_DIGEST = null;
    		try {
    			MESSAGE_DIGEST = MessageDigest.getInstance("MD5");
    		} catch (NoSuchAlgorithmException ex) {
    			log.error("error initializing Message Digest "+ex);
    		}
    		return MESSAGE_DIGEST;
    	};
	};
        
    private static final Manager manager = new Manager();;

	private Manager() {
		super();
		JSON_ELEMENT = FileHandler.getJSONElement(SocketConnectorConstants.DOMAIN_CONFIG_FILE);
		createAppConfigs();
		creatIdMapping(JSON_ELEMENT);
	}
	
	public static Manager getManager(){
		return manager;
	}
	
	public static String getStackTrace(Throwable th){
		StringBuilder error = new StringBuilder();
    	StackTraceElement[] stacks = th.getStackTrace();
    	for(StackTraceElement stack : stacks){
    		error.append(stack.toString()).append("\n");
    	}
    	return error.toString();
	}
	
	public static String createPayLoad(List<SourceRecord> records){
		StringBuilder payload = new StringBuilder();
		if(records == null || records.isEmpty()){
			return payload.toString();
		}
		
		for(SourceRecord record : records){
			payload.append(record).append("\n");
		}
		return payload.toString();
	}
	
	public static RxServer<ByteBuf, ByteBuf> startTCPServer(final int port , final BulkProcessor processor){
		//initialize tcp server helper
		RxNettyTCPServer serverHelper = new RxNettyTCPServer(port);
        new Thread(serverHelper).start();
        if(processor != null){
        	processor.start();
        }
        
        try {
			Thread.sleep(5000);
		} catch (InterruptedException ex) {
			log.error("Error while starting TCP server thread" +ex.getMessage());
		}
        RxServer<ByteBuf, ByteBuf> nettyServer = serverHelper.getNettyServer();
        return nettyServer;
	}
	
	private static void createAppConfigs(){
		InputStream in = FileHandler.getResourceAsStream(SocketConnectorConstants.APP_CONFIG_FILE);
		try {
			APP_CONFIGS.load(in);
		} catch (IOException ex) {
			log.error(" Error while loading app config"+ex);
			throw new RuntimeException(ex);
		}
	}
	
	public static void creatIdMapping(final JsonElement element){
		Gson gson = new Gson();
        if ( element.isJsonObject()) {
            JsonObject jsonConfig =  element.getAsJsonObject();
            String msgIdString = APP_CONFIGS.getProperty(SocketConnectorConstants.ALL_CONFIG_NAMES);
            String[] msgIds = msgIdString.split(",");
            for(String msgId : msgIds){
            	Set<String> values = new HashSet<>();
            	String jsonArry = jsonConfig.get(msgId).getAsString();
            	
            	values.addAll(Arrays.asList(jsonArry.split(",")));
            	ID_MAP.put(msgId, values);
            	if (msgId.equals(SocketConnectorConstants.DOMAIN_TOPIC_MAPPING)) {
					Type stringStringMap = new TypeToken<Map<String, String>>() {}.getType();
					Map<String, String> domainTopic = gson.fromJson("{"+jsonArry+"}", stringStringMap);
					DOMAIN_TOPIC_MAP.putAll(domainTopic);

				} 
            }
     
        }
        
	}
	
	public static void reMapDomainConfigurations(Map<String,String> taskMap){
		Set<Entry<String, Set<String>>> enrties = ID_MAP.entrySet();
        for(Entry<String, Set<String>> entry : enrties){
        	Set<String> value = entry.getValue();
        	String key = entry.getKey();
        	StringBuilder valueString = new StringBuilder();
        	for (String each : value) {
				if(valueString.length() > 0){
					valueString.append(",");
				}
				valueString.append(each);
			}
        	taskMap.put(key, valueString.toString());
        }
        
	}
	
	public static void reMapDomainConfigurations(final SocketConnectorConfig config, Map<String,String> taskMap,
			Map<String, String> domainTopicMap, Map<String,Set<String>> idMap){
		String msgIdString = taskMap.get(SocketConnectorConstants.ALL_CONFIG_NAMES);
        String[] msgIds = msgIdString.split(",");
        for(String msgId : msgIds){
        	List<String> domainConfigValues =  config.getList(msgId);
        	if(domainConfigValues == null || domainConfigValues.isEmpty()){
        		continue;
        	}
        	Set<String> values = new HashSet<>(domainConfigValues);
        	idMap.put(msgId, values);
        	if (msgId.equals(SocketConnectorConstants.DOMAIN_TOPIC_MAPPING)) {
        		parseMapConfig(domainConfigValues,domainTopicMap);
        	}
        }
        
	}
	
	
	
	public static void parseMapConfig(final List<String> values, final Map<String,String> map) {
		for (String value : values) {
			String[] parts = value.split(":");
			String domainName = parts[0];
			String topic = parts[1];
			map.put(domainName, topic);
		}
	}
	
	public static void addAllConfigToConnectMap(final Map<String, String> map) {
		String msgIdString = APP_CONFIGS.getProperty(SocketConnectorConstants.ALL_CONFIG_NAMES);
		map.put(SocketConnectorConstants.ALL_CONFIG_NAMES, msgIdString);
	}
	
	
	public static String generateKeysUsingFields(JsonObject payload, String msgKeyName, Map<String,Set<String>> idMap){
		Set<String> msgIdNames = idMap.get(msgKeyName);
		StringBuilder keyAppender = new StringBuilder();
		for(String id : msgIdNames){
			//id is one of the values of message_partition_key.json
			keyAppender.append(payload.get(id).getAsString());
		}
		
		return keyAppender.toString();
		
	}
		
	public static String getMD5HexValue(final String key){
		MD_TL.get().update(key.getBytes());
		byte[] digest = MD_TL.get().digest();
		StringBuffer sb = new StringBuffer();
		for (byte b : digest) {
			sb.append(String.format("%02x", b & 0xff));
		}
		
		return sb.toString();
	}
	
	public static JsonObject getJsonObject(final String payload ){
		JsonParser parser = new JsonParser();
		JsonElement element = null;
		JsonObject jsonObject = null;
		try {
			element = parser.parse(payload);
			jsonObject = element.getAsJsonObject();
		} catch (Exception ex) {
			log.error(" Error while reading " + ex);
		}
		return jsonObject;
	}
	
	public static String addFieldToPayload(final JsonObject jsonObject, final Map<String,String> fieldValue){
		String updatedPayload = null;
		try {
			Set<Entry<String, String>> entries = fieldValue.entrySet();
			for(Entry<String, String> entry : entries){
				jsonObject.addProperty(entry.getKey(), entry.getValue());
			}
			updatedPayload = jsonObject.toString();
		} catch (Exception ex) {
			log.error(" Error while reading " + ex);
		}
		return updatedPayload;
	}
	
	public static void dumpConfiguration(Map<String, String> map) {
		log.info("Starting connector with configuration:");
		StringBuilder config = new StringBuilder();
		for (Map.Entry entry : map.entrySet()) {
			config.append(entry.getKey() + " : " + entry.getValue() + "\n");
		}
		log.info("{}: {}", " Standalone Configuration", config.toString());
	}

}
