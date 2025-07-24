package com.aipaas.anycloud.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.net.UnknownHostException;

public class Common {

	public static boolean isValidUUID(String uuidString) {
		// UUID 형식을 검증하는 정규 표현식
		String uuidRegex = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
		// 입력된 문자열이 UUID 형식과 일치하는지 확인
		return Pattern.matches(uuidRegex, uuidString);
	}

}
