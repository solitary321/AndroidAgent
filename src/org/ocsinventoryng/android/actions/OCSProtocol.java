package org.ocsinventoryng.android.actions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.ocsinventoryng.android.agent.OCSPrologReply;
import org.ocsinventoryng.android.agent.R;

import android.content.Context;

public class OCSProtocol {
	private OCSLog ocslog = OCSLog.getInstance();
	private OCSFiles ocsfile;
	private String http_agent;
	private Context appCtx;

	public OCSProtocol(Context context) {
		ocsfile = new OCSFiles(context);
		appCtx=context;
		if ( OCSSettings.getInstance().isCompUAEnabled() )
			http_agent=context.getString(R.string.comp_useragent);
		else
			http_agent=context.getString(R.string.useragent);
		ocslog.debug("USERAGENT "+http_agent);
	}

	public OCSPrologReply sendPrologueMessage(Inventory inv) throws OCSProtocolException {
		ocslog.debug("Start Sending Prolog...");
		String repMsg;
		File localFile = ocsfile.getPrologFileXML();
		String sURL = OCSSettings.getInstance().getServerUrl();
		// boolean gz = OCSSettings.getInstance().getGzip();
		
		repMsg = sendmethod(localFile, sURL, true);
		ocslog.debug("Finnish Sending Prolog...");
		String freq=extractFreq(repMsg);
		if  ( freq.length() > 0 )
			OCSSettings.getInstance().setFreqMaj(freq);
		PrologReplyParser prp =new PrologReplyParser();
		// reponse = extractResponse(repMsg);
		// Save reply 
		ocsfile.savePrologReply(repMsg);
		return prp.parseDocument(repMsg);
	}
	public String sendRequestMessage(String query, String id, String err) throws OCSProtocolException {
		ocslog.debug("Start Sending Request "+query+","+id+","+err);
		String repMsg;
		File localFile = ocsfile.getRequestFileXML(query,  id,  err);
		String sURL = OCSSettings.getInstance().getServerUrl();
		// boolean gz = OCSSettings.getInstance().getGzip();
		
		repMsg = sendmethod(localFile, sURL, false);
		ocslog.debug("Finnish Sending Request...");

		String reponse = extractResponse(repMsg);
		// Save reply 
		return(reponse);
	}
	
	public String sendInventoryMessage(Inventory inventory) throws OCSProtocolException {
		ocslog.debug("Start Sending Inventory...");
		String retour = null;		
		// boolean gz = OCSSettings.getInstance().getGzip();
		
		File invFile = ocsfile.getInventoryFileXML(inventory);
		String sURL = OCSSettings.getInstance().getServerUrl();

		String repMsg  = sendmethod(invFile, sURL, true);
		
		retour = extractResponse(repMsg);
		// upload ok. Save current sections fingerprints values
		inventory.saveSectionsFP();
		invFile.delete();
		ocslog.debug("Finnish Sending Inventory..."+retour);
		return retour;
	}
	
	public DefaultHttpClient getNewHttpClient(boolean strict ) {
	    try {
	        SSLSocketFactory sf;
	    	if ( strict ) {
	    		sf = SSLSocketFactory.getSocketFactory();
	    	}
	    	else {
	    		KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
	        	trustStore.load(null, null);
	        	sf = new CoolSSLSocketFactory(trustStore);
	    	}

	    	HttpParams params = new BasicHttpParams();
	        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
	        HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);

	        SchemeRegistry registry = new SchemeRegistry();
	        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
	        registry.register(new Scheme("https", sf, 443));

	        ClientConnectionManager ccm = new ThreadSafeClientConnManager(params, registry);

		    HttpConnectionParams.setConnectionTimeout(params, 10000);
		    HttpConnectionParams.setSoTimeout(params, 10000);
 		    
	        return new DefaultHttpClient(ccm, params);
	    } catch (Exception e) {
	        return new DefaultHttpClient();
	    }
	}
	
	public String sendmethod(File pFile, String server, boolean gziped)	throws OCSProtocolException {
		OCSLog ocslog = OCSLog.getInstance();
		OCSSettings ocssettings = OCSSettings.getInstance();
		ocslog.debug("Start send method");
		String retour;

		HttpPost httppost = null;
				
		try {
			httppost = new HttpPost(server);
		} catch ( IllegalArgumentException e ) {
			ocslog.error(e.getMessage());
			throw new OCSProtocolException("Incorect serveur URL");
		}
		
		// FileEntity localFileEntity = new FileEntity(paramFile, "application/x-compress; charset=\"UTF-8\"");
 
		File fileToPost;
		if ( gziped ) {
			ocslog.debug("Start compression");
			fileToPost=ocsfile.getGzipedFile(pFile);
			if ( fileToPost == null )
				throw new OCSProtocolException("Error during temp file creation");
			ocslog.debug("Compression done");
		}
		else
			fileToPost=pFile;
		
		FileEntity fileEntity = new FileEntity(fileToPost, "text/plain; charset=\"UTF-8\"");
		httppost.setEntity(fileEntity);
		httppost.setHeader("User-Agent", http_agent);
		if ( gziped ) {
			httppost.setHeader("Content-Encoding", "gzip");
		}
		
		DefaultHttpClient httpClient = getNewHttpClient(OCSSettings.getInstance().isSSLStrict());

		if  ( ocssettings.isProxy()) {
			ocslog.debug("Use proxy : " + ocssettings.getProxyAdr());
			HttpHost proxy = new HttpHost(ocssettings.getProxyAdr(), ocssettings.getProxyPort());
		    httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);	       
		}
		if  ( ocssettings.isAuth()) {
			ocslog.debug("Use AUTH : " + ocssettings.getLogin()+"/*****");
			/*
			CredentialsProvider credProvider = new BasicCredentialsProvider();
	        credProvider.setCredentials(new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
	            new UsernamePasswordCredentials(ocssettings.getLogin(), ocssettings.getPasswd()));
	        */
			UsernamePasswordCredentials  creds = new UsernamePasswordCredentials(ocssettings.getLogin(), ocssettings.getPasswd());
			ocslog.debug(creds.toString());
			httpClient.getCredentialsProvider().setCredentials(
	        		new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
	        		creds);
		}
  		

			ocslog.debug("Call : " + server);
			HttpResponse localHttpResponse=null;
			try {
				localHttpResponse = httpClient.execute(httppost);
			} catch (ClientProtocolException e) {
				throw new OCSProtocolException(e.getMessage());
			} 
			catch (IOException e) {
				String msg = appCtx.getString(R.string.err_cant_connect)+" "+e.getMessage();
				ocslog.error(msg);
				throw new OCSProtocolException(msg);
			}
			
			try {		
			int httpCode = localHttpResponse.getStatusLine().getStatusCode();
			ocslog.debug("Response status code : " + String.valueOf(httpCode));
			if ( httpCode== 200) {
				if  ( gziped ) {
					InputStream is = localHttpResponse.getEntity().getContent();
					GZIPInputStream gzis = new GZIPInputStream(is);
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					byte[] buff = new byte[128];
					int n;
					while ( (n = gzis.read(buff,0, buff.length)) > 0)  {
						baos.write(buff,0,n);
					}
					retour=baos.toString();
				} else
					retour=EntityUtils.toString(localHttpResponse.getEntity());
			
			}
			else if ( httpCode == 400) {
				throw new OCSProtocolException("Error http 400 may be wrong agent version");
			}
			else {
				ocslog.error("***Server communication error: ");
				throw new OCSProtocolException("Http communication error code "+String.valueOf(httpCode));
			}
			ocslog.debug("Finnish send method");
		} catch (IOException localIOException) {
			String msg = localIOException.getMessage();
			ocslog.error(msg);
			throw new OCSProtocolException(msg);
		}
		return retour;
	}
	
	public void downloadFile(String url, String fileName) throws OCSProtocolException {
		DefaultHttpClient httpClient = getNewHttpClient(OCSSettings.getInstance().isSSLStrict());
		HttpGet httpget = new HttpGet(url);
		httpget.setHeader("User-Agent", http_agent);
		
		HttpResponse httpResponse=null;
		try {
			httpResponse = httpClient.execute(httpget);
		} catch (Exception e) {
			throw new OCSProtocolException("Cant connect "+url);
		}
		int httpCode = httpResponse.getStatusLine().getStatusCode();
		ocslog.debug("Response status code : " + String.valueOf(httpCode));
		if ( httpCode == 200) {
			byte[] buff = new byte[4096];
			try {
				InputStream is = httpResponse.getEntity().getContent();
				File fout = new File(appCtx.getFilesDir(),fileName);
				FileOutputStream fos = new FileOutputStream(fout);
				int n=0;
				while ( (n=is.read(buff)) > -1) {
					fos.write(buff,0,n);
				}
				fos.close();
			} catch (IOException e) {
				throw new OCSProtocolException("IOException ");
			}
		} else 
		if ( httpCode == 400) {
			throw new OCSProtocolException("Error 400 may be wrong agent version");
		}
		else {
			throw new OCSProtocolException("Http error code "+String.valueOf(httpCode));
		}
	}
	
	public String strwget(String url) throws OCSProtocolException {
		DefaultHttpClient httpClient = getNewHttpClient(OCSSettings.getInstance().isSSLStrict());
		HttpGet httpget = new HttpGet(url);
		httpget.setHeader("User-Agent", http_agent);
		
		HttpResponse httpResponse=null;
		try {
			httpResponse = httpClient.execute(httpget);
		} catch (Exception e) {
			throw new OCSProtocolException("Cant connect "+url);
		}
		int httpCode = httpResponse.getStatusLine().getStatusCode();
		ocslog.debug("Response status code : " + String.valueOf(httpCode));
		if ( httpCode == 200) {
			byte[] buff = new byte[1024];
			try {
				InputStream is = httpResponse.getEntity().getContent();
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				int n=0;
				while ( (n=is.read(buff)) > -1) {
					baos.write(buff,0,n);
				}
				baos.close();
				return baos.toString();
			} catch (IOException e) {
				throw new OCSProtocolException("IOException ");
			}
		} else {
				throw new OCSProtocolException("Http error code "+String.valueOf(httpCode));
		}
	}
	
	private String extractResponse( String message ) {
		String resp = "";
		Pattern p = Pattern.compile(".*<RESPONSE>(.*)</RESPONSE>.*", Pattern.CASE_INSENSITIVE);
		Matcher m= p.matcher(message);
		if ( m.find() ) 
			return m.group(1);
		return resp;
	}
	private String extractFreq( String message ) {
		String resp = "";
		Pattern p = Pattern.compile(".*<PROLOG_FREQ>(.*)</PROLOG_FREQ>.*", Pattern.CASE_INSENSITIVE);
		Matcher m= p.matcher(message);
		if ( m.find() ) 
			return m.group(1);
		return resp;
	}
	
	public void verifyNewVersion(int pvcode) {
		File uptdflag = appCtx.getFileStreamPath("update.flag");
		if ( uptdflag.exists() ) {
			try {
				String uctx= Utils.readShortFile(uptdflag);
				OCSLog.getInstance().debug("uctx :"+uctx);
				String[]str = uctx.split(";");
				if ( str.length > 1  ) {
					String id=str[0];
					int vcode=Integer.parseInt(str[1]);
					ocslog.debug("Test version code :"+vcode+"="+pvcode);
					if ( vcode == pvcode) 
							sendRequestMessage("DOWNLOAD", id, "SUCCESS");
					// else
					//	ocsproto.sendRequestMessage("DOWNLOAD", id, "ERR_ABORT");
					}
			} catch (IOException e) {
				ocslog.error("Cant read update.flag");
			} catch (OCSProtocolException e) {
				ocslog.error(e.getMessage());
			}
			if ( ! uptdflag.delete() )
				ocslog.error("Cant delete update.flag");
		}
	}
	
}