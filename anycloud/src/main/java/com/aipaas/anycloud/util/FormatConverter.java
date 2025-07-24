package com.aipaas.anycloud.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Log4j2
public class FormatConverter {

	public static String toJson(Object object) {
		ObjectMapper mapper = new ObjectMapper();
		String json = null;

		try {
			json = mapper.writeValueAsString(object);
		} catch (JsonProcessingException e) {
			log.error(e.getMessage());
		}

		return json;
	}

	public static String camelToSnake(String str) {
		String result = "";

		char c = str.charAt(0);
		result = result + Character.toLowerCase(c);

		for (int i = 1; i < str.length(); i++) {

			char ch = str.charAt(i);

			if (Character.isUpperCase(ch)) {
				result = result + '-';
				result = result
						+ Character.toLowerCase(ch);
			} else {
				result = result + ch;
			}
		}
		return result;
	}

	public static String convertInputStreamToString(InputStream inputStream) {
		StringBuilder stringBuilder = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				stringBuilder.append(line);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return stringBuilder.toString();
	}

	public static String convertInputBytes(long bytes) {
		long kilobyte = 1024;
		long megabyte = kilobyte * 1024;
		long gigabyte = megabyte * 1024;
		long terabyte = gigabyte * 1024;

		if ((bytes >= 0) && (bytes < kilobyte)) {
			return bytes + "B";

		} else if ((bytes >= kilobyte) && (bytes < megabyte)) {
			return (bytes / kilobyte) + "KB";

		} else if ((bytes >= megabyte) && (bytes < gigabyte)) {
			return (bytes / megabyte) + "MB";

		} else if ((bytes >= gigabyte) && (bytes < terabyte)) {
			return (bytes / gigabyte) + "GB";

		} else if (bytes >= terabyte) {
			return (bytes / terabyte) + "TB";

		} else {
			return bytes + "Bytes";
		}
	}
}