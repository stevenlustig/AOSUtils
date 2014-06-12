package org.aosutils.android.youtube;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.aosutils.StringUtils;
import org.aosutils.android.AOSUtilsCommon;
import org.aosutils.net.HttpUtils;

import android.text.TextUtils;


public class YtApiSignature {	
	/*
	 *  Thanks to the great documentation at youtubedown, I've been able to replicate this in Java:
	 *  youtubedown: http://www.jwz.org/hacks/youtubedown
	 */
	
	private static class Html5PlayerInfo {
		String version;
		String url;
	}
	
	public static String requestCurrentAlgorithm() throws FileNotFoundException, MalformedURLException, IOException {
		HashMap<String, String> headers = new HashMap<String, String>();
		headers.put("User-Agent", AOSUtilsCommon.USER_AGENT_DESKTOP);
		
		String homepage = HttpUtils.get("http://www.youtube.com", headers, _YtApiConstants.HttpTimeout);
		
		return requestCurrentAlgorithm(homepage);
	}
	
	public static String requestCurrentAlgorithm(String youtubePageSource) throws IOException {
		Html5PlayerInfo playerInfo = getHtml5PlayerInfo(youtubePageSource);
		return playerInfo == null ? null : requestCurrentAlgorithmFromHtml5PlayerUrl(playerInfo.url);
	}
	
	public static String getCurrentVersion(String youtubePageSource) {
		Html5PlayerInfo playerInfo = getHtml5PlayerInfo(youtubePageSource);
		return playerInfo == null ? null : playerInfo.version;
	}
	
	/**
	 * Same algorithm format as used in youtubedown:
	 * 
	 * r  = reverse the string;
	 * sN = slice from character N to the end;
	 * wN = swap 0th and Nth character.
	 */
	public static String decode(String url, String signature, String algorithm) {
		for (String procedure : TextUtils.split(algorithm, " ")) {
			String procedureType = procedure.substring(0, 1);
			if (procedureType.equals("r")) { // reverse the string
				signature = new StringBuilder(signature).reverse().toString();
			}
			else if (procedureType.equals("s")) { // slice from character N to the end
				int position = Integer.parseInt(procedure.substring(1));
				signature = signature.substring(position);
			}
			else if (procedureType.equals("w")) { // swap 0th and Nth character
				int position = Integer.parseInt(procedure.substring(1));
				signature = signature.substring(position, position+1) + signature.substring(1, position) + signature.substring(0, 1) + signature.substring(position+1);
			}
		}
		
		return url + "&signature=" + signature;
	}
	
	private static Html5PlayerInfo getHtml5PlayerInfo(String youtubePageSource) {
		String regex = "html5player-([^\\s]+?)\\.js";
		
		Matcher matcher = Pattern.compile(regex).matcher(youtubePageSource);
		if (matcher.find()) {
			int pathBegin = youtubePageSource.substring(0, matcher.start()).lastIndexOf("\"") + 1;
			int pathEnd = youtubePageSource.substring(matcher.end()).indexOf("\"") + matcher.end();
			
			String playerVersion = matcher.group(1);
			
			String path = youtubePageSource.substring(pathBegin, pathEnd).replace("\\/", "/");
			if (path.startsWith("//")) {
				path = "http:" + path;
			}
			
			Html5PlayerInfo playerInfo = new Html5PlayerInfo();
			playerInfo.version = playerVersion;
			playerInfo.url = path;
			
			return playerInfo;
		}
		
		return null;
	}
	
	private static String requestCurrentAlgorithmFromHtml5PlayerUrl(String html5PlayerUrl) throws IOException {
		String playerJsSrc = HttpUtils.get(html5PlayerUrl, null, _YtApiConstants.HttpTimeout);
		return getAlgorithmFromHtml5PlayerJsSrc(playerJsSrc);
	}
		
	private static String getAlgorithmFromHtml5PlayerJsSrc(String jsSrc) throws IOException {
		// Find "C" in this: var A = B.sig || C (B.s), this will be the name of the signature swapping algorithm function
		String c = null;
		{
			String regex = "var (.+)\\=(.+)\\.sig\\|\\|(.+?)\\((.+?)\\.s\\)";
			Matcher matcher = Pattern.compile(regex).matcher(jsSrc);
			if (matcher.find()) {
				c = matcher.group(3);
			}
		}
		
		// Find body of function C(D) { ... }, this is the signature swapping algorithm function :)
		String body = null;
		if (c != null) {
			{
				String regex = "function " + c + "\\((.+?)\\)\\{(.+?)\\}";
				Matcher matcher = Pattern.compile(regex).matcher(jsSrc);
				if (matcher.find()) {
					body = matcher.group(2);
				}
			}
		}
		
		if (body != null) {
			// They inline the swapper if it's used only once.
			// Convert "var b=a[0];a[0]=a[63%a.length];a[63]=b;" to "a=swap(a,63);".
			{
				String regex = "var (.+?)=(.+?)\\[(.+?)\\];(.+?)\\[(.+?)\\]=(.+?)\\[(.+?)%(.+?)\\.length\\];(.+?)\\[(.+?)\\]=(.+?);";
				body = replaceAll(body, regex, new int[] {6, 6, 7}, "%s=swap(%s,%s);");
			}
			
			/*
			 * Handle procedures
			 */
			
			// Split
			body = replaceAll(body, "[^;]+=[^;]+\\.split\\(\"\"\\)[^;]*", new int[] { }, "");
			
			// Reverse
			body = replaceAll(body, "[^;]+=[^;]+\\.reverse\\(\\)[^;]*", new int[] { }, "r");
			
			// Swap
			body = replaceAll(body, "[^;]+=[^;]+\\([^;]+,(.+?)\\)[^;]*", new int[] { 1 }, "w%s");
			
			// Slice
			body = replaceAll(body, "[^;]+=[^;]+\\.slice\\((.*?)\\)[^;]*", new int[] { 1 }, "s%s");
			
			// Join
			body = replaceAll(body, "[^;]*return [^;]+\\.join\\(\"\"\\)", new int[] { }, "");
		}
		
		body = body.replace(";", " ").trim();
		
		return body;
	}
	
	private static String replaceAll(String document, String regexToFind, int[] valueIndicesToExtract, String formattedStringToReplace) {
		Matcher matcher = Pattern.compile(regexToFind).matcher(document);
		while (matcher.find()) {
			String match = document.substring(matcher.start(), matcher.end());
			
			ArrayList<String> extractedValues = new ArrayList<String>();
			
			for (int valueIndexToExtract : valueIndicesToExtract) {
				String extractedValue = matcher.group(valueIndexToExtract);
				extractedValues.add(extractedValue);
			}
			
			String replaceString = StringUtils.format(formattedStringToReplace, extractedValues);
			String regexEscapedMatch = Pattern.quote(match);
			
			document = document.replaceFirst(regexEscapedMatch, replaceString);
			
			// Reset matcher for new document (with new replacements)
			matcher = Pattern.compile(regexToFind).matcher(document);
		}
		
		return document;
	}
}