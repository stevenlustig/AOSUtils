package org.aosutils.net;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;

import org.aosutils.IoUtils;

public class HttpUtils {
	public static class HTTPException extends IOException {
		private static final long serialVersionUID = -4486771807423795451L;
		
		public HTTPException(String message) {
			super(message);
		}
	}
	public static class HTTPUnauthorizedException extends HTTPException {
		private static final long serialVersionUID = -58562254193206846L;
		
		public HTTPUnauthorizedException(String message) {
			super(message);
		}
	}
	
	public static String get(String url, Map<String, String> headers, Integer httpTimeout) throws FileNotFoundException, MalformedURLException, IOException {
		return request(url, headers, null, httpTimeout, null, false);
	}
	public static String post(String url, Map<String, String> headers, String postData, Integer httpTimeout) throws FileNotFoundException, MalformedURLException, IOException {
		postData = (postData == null ? "" : postData);
		return request(url, headers, postData, httpTimeout, null, false);
	}
	
	public static String request(String url, Map<String, String> headers, String postData, Integer httpTimeout, Proxy proxy, boolean forceTrustSSLCert) throws FileNotFoundException, MalformedURLException, IOException {
		InputStream inputStream = requestStream(url, headers, postData, httpTimeout, proxy, forceTrustSSLCert);
		return IoUtils.getString(inputStream);
	}
	public static InputStream requestStream(String url, Map<String, String> headers, String postData, Integer httpTimeout, Proxy proxy, boolean forceTrustSSLCert) throws FileNotFoundException, MalformedURLException, IOException {
		URL urlObj = new URL(url);
		URLConnection urlConnection = proxy == null ? urlObj.openConnection() : urlObj.openConnection(proxy);
		
		if (httpTimeout != null) {
			urlConnection.setConnectTimeout(httpTimeout);
			urlConnection.setReadTimeout(httpTimeout);
		}
		
		if (forceTrustSSLCert == true && urlConnection instanceof HttpsURLConnection) {
			try {
				HttpsForceTrustCertManager.getInstance().forceTrustSSLCert( (HttpsURLConnection) urlConnection);
			} catch (KeyManagementException | NoSuchAlgorithmException e) {
				e.printStackTrace();
			}
		}
		
		if (headers == null) {
			headers = new HashMap<String, String>();
		}
		
		for (String param : headers.keySet()) {
			urlConnection.setRequestProperty(param, headers.get(param));
		}
		
		if (postData != null) {
			urlConnection.setDoOutput(true);
			
			if (!postData.equals("")) {
				if ("gzip".equals(headers.get("Content-Encoding"))) {
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					GZIPOutputStream gzos = new GZIPOutputStream(baos);
				    gzos.write(postData.getBytes("UTF-8"));
				    postData = new String(baos.toByteArray());
				}
			    
				IoUtils.sendToOutputStream(urlConnection.getOutputStream(), postData);
			}
		}
		
		InputStream resultStream = null;
		IOException exception = null;
		
		try {
			resultStream = urlConnection.getInputStream();
			if ("gzip".equals(urlConnection.getContentEncoding())) {
				resultStream = new GZIPInputStream(resultStream);
			}
		}
		catch (IOException e) {
			exception = e;
		}
		
		if (urlConnection instanceof HttpURLConnection) {
			HttpURLConnection httpConnection = (HttpURLConnection) urlConnection;
			
			int responseCode = httpConnection.getResponseCode();
			
			if (responseCode == 401) { // HTTP 401/Unauthorized, try to pass along message from exception
				throw new HTTPUnauthorizedException(exception != null ? exception.getMessage() : httpConnection.getResponseMessage());
			}
			else if (exception != null) { // Any other exception
				throw exception;
			}
			else if (responseCode >= 300) { // Bad HTTP response code, but no Exception (eg. HTTP 301/Moved Permanently)
				throw new HTTPException("HTTP ResponseCode: " + responseCode + ", " + httpConnection.getResponseMessage());
			}
		}
		
		return resultStream;
	}
	
	public static String getPublicIpAddress(final Integer httpTimeout) {
		final StringBuilder ipInfo = new StringBuilder();
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					//String url = "http://checkip.dyndns.org/";
					String url = "http://api.ipify.org/";
					ipInfo.append(HttpUtils.get(url, null, httpTimeout));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		thread.start();
		String ip = null;
		try {
			thread.join();
			if (!("".equals(ipInfo.toString().trim()))) {
				ip = ipInfo.toString().trim();
			}
		} catch (InterruptedException e) {
		}
		
		return ip;
	}
}
