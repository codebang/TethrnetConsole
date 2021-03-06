/**
 * Copyright 2013 Sean Kavanagh - sean.p.kavanagh6@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tethrnet.manage.util;

import com.jcraft.jsch.*;
import com.tethrnet.common.util.AppConfig;
import com.tethrnet.manage.db.*;
import com.tethrnet.manage.model.*;
import com.tethrnet.manage.task.SecureShellTask;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSH utility class used to create public/private key for system and distribute authorized key files
 */
public class SSHUtil {


    private static Logger log = LoggerFactory.getLogger(SSHUtil.class);
    public static final boolean keyManagementEnabled = "true".equals(AppConfig.getProperty("keyManagementEnabled"));

	//system path to public/private key
	public static final String KEY_PATH = DBUtils.class.getClassLoader().getResource("tethrnetdb").getPath();

	//key type - rsa or dsa
	public static final String KEY_TYPE = AppConfig.getProperty("sshKeyType");
	public static final int KEY_LENGTH= StringUtils.isNumeric(AppConfig.getProperty("sshKeyLength")) ? Integer.parseInt(AppConfig.getProperty("sshKeyLength")) : 2048;

	//private key name
	public static final String PVT_KEY = KEY_PATH + "/id_" + KEY_TYPE;
	//public key name
	public static final String PUB_KEY = PVT_KEY + ".pub";


	public static final int SERVER_ALIVE_INTERVAL = StringUtils.isNumeric(AppConfig.getProperty("serverAliveInterval")) ? Integer.parseInt(AppConfig.getProperty("serverAliveInterval")) * 1000 : 60 * 1000;
	public static final int SESSION_TIMEOUT = 60000;
	public static final int CHANNEL_TIMEOUT = 60000;

	/**
	 * returns the system's public key
	 *
	 * @return system's public key
	 */
	public static String getPublicKey() {

		String publicKey = PUB_KEY;
		//check to see if pub/pvt are defined in properties
		if (StringUtils.isNotEmpty(AppConfig.getProperty("privateKey")) && StringUtils.isNotEmpty(AppConfig.getProperty("publicKey"))) {
			publicKey = AppConfig.getProperty("publicKey");
		}
		//read pvt ssh key
		File file = new File(publicKey);
		try {
			publicKey = FileUtils.readFileToString(file);
		} catch (Exception ex) {
			log.error(ex.toString(), ex);
		}

		return publicKey;
	}


	/**
	 * returns the system's public key
	 *
	 * @return system's public key
	 */
	public static String getPrivateKey() {

		String privateKey = PVT_KEY;
		//check to see if pub/pvt are defined in properties
		if (StringUtils.isNotEmpty(AppConfig.getProperty("privateKey")) && StringUtils.isNotEmpty(AppConfig.getProperty("publicKey"))) {
			privateKey = AppConfig.getProperty("privateKey");
		}

		//read pvt ssh key
		File file = new File(privateKey);
		try {
			privateKey = FileUtils.readFileToString(file);
		} catch (Exception ex) {
			log.error(ex.toString(), ex);
		}

		return privateKey;
	}

	/**
	 * generates system's public/private key par and returns passphrase
	 *
	 * @return passphrase for system generated key
	 */
	public static String keyGen() {


		//get passphrase cmd from properties file
		Map<String, String> replaceMap = new HashMap<String, String>();
		replaceMap.put("randomPassphrase", UUID.randomUUID().toString());

		String passphrase = AppConfig.getProperty("defaultSSHPassphrase", replaceMap);

		AppConfig.updateProperty("defaultSSHPassphrase", "${randomPassphrase}");

		return keyGen(passphrase);

	}

	/**
	 * delete SSH keys
	 */
	public static void deleteGenSSHKeys() {

		deletePvtGenSSHKey();
		//delete public key
		try {
			File file = new File(PUB_KEY);
			FileUtils.forceDelete(file);
		} catch (Exception ex) {
		}
	}


	/**
	 * delete SSH keys
	 */
	public static void deletePvtGenSSHKey() {

		//delete private key
		try {
			File file = new File(PVT_KEY);
			FileUtils.forceDelete(file);
		} catch (Exception ex) {
		}


	}

	/**
	 * generates system's public/private key par and returns passphrase
	 *
	 * @return passphrase for system generated key
	 */
	public static String keyGen(String passphrase) {

		deleteGenSSHKeys();

		if (StringUtils.isEmpty(AppConfig.getProperty("privateKey")) || StringUtils.isEmpty(AppConfig.getProperty("publicKey"))) {

			//set key type
			int type = KeyPair.RSA;
			if(SSHUtil.KEY_TYPE.equals("dsa")) {
				type = KeyPair.DSA;
			} else if(SSHUtil.KEY_TYPE.equals("ecdsa")) {
				type = KeyPair.ECDSA;
			}
			String comment = "keybox@global_key";

			JSch jsch = new JSch();

			try {

				KeyPair keyPair = KeyPair.genKeyPair(jsch, type, KEY_LENGTH);

				keyPair.writePrivateKey(PVT_KEY, passphrase.getBytes());
				keyPair.writePublicKey(PUB_KEY, comment);
                System.out.println("Finger print: " + keyPair.getFingerPrint());
				keyPair.dispose();
			} catch (Exception e) {
				log.error(e.toString(), e);
			}
		}


		return passphrase;


	}

	/**
	 * distributes authorized keys for host system
	 *
	 * @param hostSystem      object contains host system information
	 * @param passphrase      ssh key passphrase
	 * @param password        password to host system if needed
	 * @return status of key distribution
	 */
	public static HostSystem authAndAddPubKey(HostSystem hostSystem, String passphrase, String password) {


		JSch jsch = new JSch();
		Session session = null;
		hostSystem.setStatusCd(HostSystem.SUCCESS_STATUS);
		try {
			ApplicationKey appKey = PrivateKeyDB.getApplicationKey();
			//check to see if passphrase has been provided
			if (passphrase == null || passphrase.trim().equals("")) {
				passphrase = appKey.getPassphrase();
				//check for null inorder to use key without passphrase
				if (passphrase == null) {
					passphrase = "";
				}
			}
			//add private key
			jsch.addIdentity(appKey.getId().toString(), appKey.getPrivateKey().trim().getBytes(), appKey.getPublicKey().getBytes(), passphrase.getBytes());

			//create session
			session = jsch.getSession(hostSystem.getUser(), hostSystem.getHost(), hostSystem.getPort());

			//set password if passed in
			if (password != null && !password.equals("")) {
				session.setPassword(password);
			}
			session.setConfig("StrictHostKeyChecking", "no");
			session.setServerAliveInterval(SERVER_ALIVE_INTERVAL);
			session.connect(SESSION_TIMEOUT);


			addPubKey(hostSystem, session, appKey.getPublicKey());

		} catch (Exception e) {
			log.info(e.toString(), e);
			hostSystem.setErrorMsg(e.getMessage());
			if (e.getMessage().toLowerCase().contains("userauth fail")) {
				hostSystem.setStatusCd(HostSystem.PUBLIC_KEY_FAIL_STATUS);
			} else if (e.getMessage().toLowerCase().contains("auth fail") || e.getMessage().toLowerCase().contains("auth cancel")) {
				hostSystem.setStatusCd(HostSystem.AUTH_FAIL_STATUS);
			} else if (e.getMessage().toLowerCase().contains("unknownhostexception")){
				hostSystem.setErrorMsg("DNS Lookup Failed");
				hostSystem.setStatusCd(HostSystem.HOST_FAIL_STATUS);
			} else {
				hostSystem.setStatusCd(HostSystem.GENERIC_FAIL_STATUS);
			}


		}

		if (session != null) {
			session.disconnect();
		}

		return hostSystem;


	}

	public static List<String> listDir(HostSystem hostSystem,SchSession session)
	{
		Channel channel = null;
		ChannelSftp c = null;
		String remote_dir = AppConfig.getProperty("remote_download_dir");
		List<String> fileList = new ArrayList<String>();
		try {

			channel = session.getSession().openChannel("sftp");

			c = (ChannelSftp) channel;
			channel.setInputStream(System.in);
			channel.setOutputStream(System.out);
			channel.connect(CHANNEL_TIMEOUT);
			
			System.out.println(c.getHome());
			
			Vector<ChannelSftp.LsEntry> files = c.ls(remote_dir);
			
			 for(int i=0; i<files.size();i++){
				 String fileName = files.get(i).getFilename();
				 if(fileName.equals(".") || fileName.equals(".."))
				 {
					 continue;
				 }
				 fileList.add(fileName);
			 }

		} catch (Exception e) {
			log.info(e.toString(), e);
			e.printStackTrace();
			hostSystem.setErrorMsg(e.getMessage());
			hostSystem.setStatusCd(HostSystem.GENERIC_FAIL_STATUS);
		}
		//exit
		if (c != null) {
			c.exit();
		}
		//disconnect
		if (channel != null) {
			channel.disconnect();
		}

		return fileList;
	}
	
	
	public static String downloadFile(SchSession session,String fileName) throws Exception
	{
		HostSystem hostSystem = session.getHostSystem();
		Channel channel = null;
		ChannelSftp c = null;
		String remote_dir = AppConfig.getProperty("remote_download_dir");
		String local_dir = AppConfig.getProperty("local_download_dir");
		safeMkdir(local_dir);
		String local_home_dir = local_dir + File.separator + hostSystem.getUser();
		safeMkdir(local_home_dir);
		String local_cache_dir = local_home_dir + File.separator + RandomString(10);
		safeMkdir(local_cache_dir);
		String temp_file = local_cache_dir + hostSystem.getDisplayNm() + "_" + fileName;
		FileUtils.touch(new File(temp_file));
		String remote_file = remote_dir + File.separator + fileName;
		try {
			
			OutputStream ouputstream = new FileOutputStream(new File(temp_file));
			channel = session.getSession().openChannel("sftp");
			channel.setInputStream(System.in);
			channel.setOutputStream(System.out);
			channel.connect(CHANNEL_TIMEOUT);

			c = (ChannelSftp) channel;
			
			c.cd(remote_dir);
			c.get(remote_file, ouputstream);
			ouputstream.close();
			
		} catch (Exception e) {
			log.info(e.toString(), e);
			return null;
		}
		//exit
		if (c != null) {
			c.exit();
		}
		//disconnect
		if (channel != null) {
			channel.disconnect();
		}

		return temp_file;
	}

	/**
	 * distributes uploaded item to system defined
	 *
	 * @param hostSystem  object contains host system information
	 * @param session     an established SSH session
	 * @param source      source file
	 * @param destination destination file
	 * @return status uploaded file
	 */
	public static HostSystem pushUpload(HostSystem hostSystem, Session session, String source, String destination) {


		hostSystem.setStatusCd(HostSystem.SUCCESS_STATUS);
		Channel channel = null;
		ChannelSftp c = null;

		try {


			channel = session.openChannel("sftp");
			channel.setInputStream(System.in);
			channel.setOutputStream(System.out);
			channel.connect(CHANNEL_TIMEOUT);

			c = (ChannelSftp) channel;
			destination = destination.replaceAll("~\\/|~", "");


			//get file input stream
			FileInputStream file = new FileInputStream(source);
			c.put(file, destination);
			file.close();

		} catch (Exception e) {
			log.info(e.toString(), e);
			hostSystem.setErrorMsg(e.getMessage());
			hostSystem.setStatusCd(HostSystem.GENERIC_FAIL_STATUS);
		}
		//exit
		if (c != null) {
			c.exit();
		}
		//disconnect
		if (channel != null) {
			channel.disconnect();
		}

		return hostSystem;


	}


	/**
	 * distributes authorized keys for host system
	 *
	 * @param hostSystem      object contains host system information
	 * @param session         an established SSH session
	 * @param appPublicKey    application public key value
	 * @return status of key distribution
	 */
	public static HostSystem addPubKey(HostSystem hostSystem, Session session, String appPublicKey) {

		try {
			String authorizedKeys = hostSystem.getAuthorizedKeys().replaceAll("~\\/|~", "");

			Channel channel = session.openChannel("exec");
			((ChannelExec) channel).setCommand("cat " + authorizedKeys);
			((ChannelExec) channel).setErrStream(System.err);
			channel.setInputStream(null);

			InputStream in = channel.getInputStream();
			InputStreamReader is = new InputStreamReader(in);
			BufferedReader reader = new BufferedReader(is);

			channel.connect(CHANNEL_TIMEOUT);

			String appPubKey = appPublicKey.replace("\n", "").trim();
			String existingKeys="";
			
			String currentKey;
			while ((currentKey = reader.readLine()) != null) {
				existingKeys = existingKeys + currentKey +"\n";
			}
			existingKeys = existingKeys.replaceAll("\\n$","");
			reader.close();
			//disconnect
			channel.disconnect();
			
			String newKeys="";
			if (keyManagementEnabled) {
				//get keys assigned to system
				List<String> assignedKeys = PublicKeyDB.getPublicKeysForSystem(hostSystem.getId());
				for (String key: assignedKeys) {
					newKeys = newKeys + key.replace("\n", "").trim() + "\n";
				}
				newKeys = newKeys + appPubKey;
			} else {
				if (existingKeys.indexOf(appPubKey) < 0) {
					newKeys = existingKeys + "\n" + appPubKey;
				}
				else {
					newKeys = existingKeys;
				}
			}

			if(!newKeys.equals(existingKeys)) {
				log.info("Update Public Keys  ==> " + newKeys);
				channel = session.openChannel("exec");
				((ChannelExec) channel).setCommand("echo '" + newKeys + "' > " + authorizedKeys + " && chmod 600 " + authorizedKeys);
				((ChannelExec) channel).setErrStream(System.err);
				channel.setInputStream(null);
				channel.connect(CHANNEL_TIMEOUT);
				//disconnect
				channel.disconnect();
			}

		} catch (Exception e) {
			log.info(e.toString(), e);
			hostSystem.setErrorMsg(e.getMessage());
			hostSystem.setStatusCd(HostSystem.GENERIC_FAIL_STATUS);
		}
		return hostSystem;
	}

	/**
	 * return the next instance id based on ids defined in the session map
	 *
	 * @param sessionId      session id
	 * @param userSessionMap user session map
	 * @return
	 */
	private static int getNextInstanceId(Long sessionId, Map<Long, UserSchSessions> userSessionMap ){

		Integer instanceId=1;
		if(userSessionMap.get(sessionId)!=null){

			for(Integer id :userSessionMap.get(sessionId).getSchSessionMap().keySet()) {
				if (!id.equals(instanceId) ) {

					if(userSessionMap.get(sessionId).getSchSessionMap().get(instanceId) == null) {
						return instanceId;
					}
				}
				instanceId = instanceId + 1;
			}
		}
		return instanceId;

	}
	
	
	/**
	 * open new ssh session on host system
	 *
	 * @param passphrase     key passphrase for instance
	 * @param password       password for instance
	 * @param userId         user id
	 * @param sessionId      session id
	 * @param hostSystem     host system
	 * @param userSessionMap user session map
	 * @return status of systems
	 */
	public static HostSystem openSSHTermOnSystem(String passphrase, String password, Long userId, Long sessionId, HostSystem hostSystem, Map<Long, UserSchSessions> userSessionMap) {

		JSch jsch = new JSch();

		int instanceId = getNextInstanceId(sessionId,userSessionMap);
		hostSystem.setStatusCd(HostSystem.SUCCESS_STATUS);
		hostSystem.setInstanceId(instanceId);


		SchSession schSession = null;

		try {
//			ApplicationKey appKey = PrivateKeyDB.getApplicationKey();
//			//check to see if passphrase has been provided
//			if (passphrase == null || passphrase.trim().equals("")) {
//				passphrase = appKey.getPassphrase();
//				//check for null inorder to use key without passphrase
//				if (passphrase == null) {
//					passphrase = "";
//				}
//			}
//			//add private key
//			jsch.addIdentity(appKey.getId().toString(), appKey.getPrivateKey().trim().getBytes(), appKey.getPublicKey().getBytes(), passphrase.getBytes());

			jsch.addIdentity(hostSystem.getAuthorizedKeys());
			//create session
			Session session = jsch.getSession(hostSystem.getUser(), hostSystem.getHost(), hostSystem.getPort());

			//set password if it exists
			if (password != null && !password.trim().equals("")) {
				session.setPassword(password);
			}
			session.setConfig("StrictHostKeyChecking", "no");
			session.setServerAliveInterval(SERVER_ALIVE_INTERVAL);
			session.connect(SESSION_TIMEOUT);
			Channel channel = session.openChannel("shell");
			if ("true".equals(AppConfig.getProperty("agentForwarding"))) {
				((ChannelShell) channel).setAgentForwarding(true);
			}
			((ChannelShell) channel).setPtyType("xterm");

			InputStream outFromChannel = channel.getInputStream();


			//new session output
			SessionOutput sessionOutput = new SessionOutput(sessionId, hostSystem);

			Runnable run = new SecureShellTask(sessionOutput, outFromChannel);
			Thread thread = new Thread(run);
			thread.start();


			OutputStream inputToChannel = channel.getOutputStream();
			PrintStream commander = new PrintStream(inputToChannel, true);


			channel.connect();

			schSession = new SchSession();
			schSession.setUserId(userId);
			schSession.setSession(session);
			schSession.setChannel(channel);
			schSession.setCommander(commander);
			schSession.setInputToChannel(inputToChannel);
			schSession.setOutFromChannel(outFromChannel);
			schSession.setHostSystem(hostSystem);

			//refresh keys for session
		//	addPubKey(hostSystem, session, appKey.getPublicKey());
			addPubKey(hostSystem, session, "");

		} catch (Exception e) {
			log.info(e.toString(), e);
			hostSystem.setErrorMsg(e.getMessage());
			if (e.getMessage().toLowerCase().contains("userauth fail")) {
				hostSystem.setStatusCd(HostSystem.PUBLIC_KEY_FAIL_STATUS);
			} else if (e.getMessage().toLowerCase().contains("auth fail") || e.getMessage().toLowerCase().contains("auth cancel")) {
				hostSystem.setStatusCd(HostSystem.AUTH_FAIL_STATUS);
			} else if (e.getMessage().toLowerCase().contains("unknownhostexception")){
				hostSystem.setErrorMsg("DNS Lookup Failed");
				hostSystem.setStatusCd(HostSystem.HOST_FAIL_STATUS);	
			} else {
				hostSystem.setStatusCd(HostSystem.GENERIC_FAIL_STATUS);
			}
		}


		//add session to map
		if (hostSystem.getStatusCd().equals(HostSystem.SUCCESS_STATUS)) {
			//get the server maps for user
			UserSchSessions userSchSessions = userSessionMap.get(sessionId);

			//if no user session create a new one
			if (userSchSessions == null) {
				userSchSessions = new UserSchSessions();
			}
			Map<Integer, SchSession> schSessionMap = userSchSessions.getSchSessionMap();

			//add server information
			schSessionMap.put(instanceId, schSession);
			userSchSessions.setSchSessionMap(schSessionMap);
			//add back to map
			userSessionMap.put(sessionId, userSchSessions);
		}

		SystemStatusDB.updateSystemStatus(hostSystem, userId);
		SystemDB.updateSystem(hostSystem);

		return hostSystem;
	}


	/**
	 * distributes public keys to all systems
	 */
	public static void distributePubKeysToAllSystems() {

		if (keyManagementEnabled) {
			List<HostSystem> hostSystemList = SystemDB.getAllSystems();
			for (HostSystem hostSystem : hostSystemList) {
				hostSystem = SSHUtil.authAndAddPubKey(hostSystem, null, null);
				SystemDB.updateSystem(hostSystem);
			}
		}
	}


	/**
	 * distributes public keys to all systems under profile
	 *
	 * @param profileId profile id
	 */
	public static void distributePubKeysToProfile(Long profileId) {

		if (keyManagementEnabled) {
			List<HostSystem> hostSystemList = ProfileSystemsDB.getSystemsByProfile(profileId);
			for (HostSystem hostSystem : hostSystemList) {
				hostSystem = SSHUtil.authAndAddPubKey(hostSystem, null, null);
				SystemDB.updateSystem(hostSystem);
			}
		}
	}

	/**
	 * distributes public keys to all systems under all user profiles
	 *
	 * @param userId user id
	 */
	public static void distributePubKeysToUser(Long userId) {

		if (keyManagementEnabled) {
			for (Profile profile : UserProfileDB.getProfilesByUser(userId)) {
				List<HostSystem> hostSystemList = ProfileSystemsDB.getSystemsByProfile(profile.getId());
				for (HostSystem hostSystem : hostSystemList) {
					hostSystem = SSHUtil.authAndAddPubKey(hostSystem, null, null);
					SystemDB.updateSystem(hostSystem);
				}
			}
		}
	}


	/**
	 * returns public key fingerprint
	 *
	 * @param publicKey public key 
	 * @return fingerprint of public key                     
	 */
	public static String getFingerprint(String publicKey){
		String fingerprint=null;
		if(StringUtils.isNotEmpty(publicKey)){
			try {
 				KeyPair keyPair = KeyPair.load(new JSch(), null, publicKey.getBytes());
				if(keyPair != null){
					fingerprint=keyPair.getFingerPrint();
				}
			} catch (JSchException ex){
				log.error(ex.toString(), ex);
			}
			
		}
		return fingerprint;

	}

	/**
	 * returns public key type 
	 *
	 * @param publicKey public key 
	 * @return fingerprint of public key                     
	 */
	public static String getKeyType(String publicKey){
		String keyType=null;
		if(StringUtils.isNotEmpty(publicKey)){
			try {
				KeyPair keyPair = KeyPair.load(new JSch(), null, publicKey.getBytes());
				if(keyPair != null) {
					int type =keyPair.getKeyType();
					if(KeyPair.DSA == type){
						keyType="DSA";
					} else if (KeyPair.RSA == type){
						keyType="RSA";
					} else if (KeyPair.ECDSA == type){
						keyType="ECDSA";
					} else if(KeyPair.UNKNOWN ==type){
						keyType="UNKNOWN";
					} else if(KeyPair.ERROR == type){
						keyType="ERROR";
					}
				}

			} catch (JSchException ex){
				log.error(ex.toString(), ex);
			}
		}
		return keyType;

	}

	public static String RandomString(int length) {  
	    String str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";  
	    java.util.Random random = new java.util.Random(); 
	    StringBuffer buf = new StringBuffer();  
	    for (int i = 0; i < length; i++) {  
	        int num = random.nextInt(62);  
	        buf.append(str.charAt(num));  
	    }  
	    return buf.toString();  
	}  
	
	public static String safeMkdir(String path) throws Exception
	{
		File file = new File(path);
		if (file.exists())
		{
			if(!file.isDirectory())
			{
				throw new Exception(MessageFormat.format("{0} exists, but not a directory", path));
			}
		}
		else
		{
			FileUtils.forceMkdir(file);		
		}
		return path;
	}


}
