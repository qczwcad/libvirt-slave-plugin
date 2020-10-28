package hudson.plugins.libvirt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.net.util.Base64;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Test;
import org.junit.Assert;

public class VirtualMachineSlaveTest {

    private static final String USER_AGENT = "Mozilla/5.0";
    
    private static final String HOST = "192.168.11.101";
    
    private static final String PORT = "8080";

    private static final String POST_URL = "http://" + HOST + ":" + PORT + "/generic-webhook-trigger/invoke?token=SmokeTestingJenkins";

    private static final String JOB_URL = "http://" + HOST + ":" + PORT + "/job/SmokeTestingJenkins/api/json";
    
    private static final String NextBuildNumberURL = "http://" + HOST + ":" + PORT + "/job/SmokeTestingJenkins/nextbuildnumber/submit";

    private static final String POST_PARAM = "userName=Ramesh&password=Pass@123";

    private static final String USER_AND_PASSWORD = "test:11660dfbbb8ad9a2e60e860fdabc962a8d";

    private static String getLastBuildURL() throws IOException {
        String response = sendHttpGetRequestToURL(JOB_URL);
        try {
            Object jobj = (new JSONParser()).parse(response);
            JSONObject jo = (JSONObject) jobj;
            Map lastBuild = (Map) jo.get("lastBuild");
            System.out.println(lastBuild.get("url"));
            return lastBuild.get("url").toString() + "api/json";
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return new String("");
        }
    }
    
    private static String getBuildURL(long buildNumber) {
        return "http://" + HOST + ":" + PORT + "/job/SmokeTestingJenkins/" + String.valueOf(buildNumber) + "/api/json";
    }

    private static String sendHttpGetRequestToURL(String URLString) throws IOException {
        URL obj = new URL(URLString);
        HttpURLConnection httpURLConnection = (HttpURLConnection) obj.openConnection();
        httpURLConnection.setRequestMethod("GET");
        httpURLConnection.setRequestProperty("User-Agent", USER_AGENT);
        byte[] authEncBytes = Base64.encodeBase64(USER_AND_PASSWORD.getBytes());
        String authStringEnc = new String(authEncBytes);
        httpURLConnection.setRequestProperty("Authorization", "Basic " + authStringEnc);
        int responseCode = httpURLConnection.getResponseCode();
        System.out.println("GET Response Code :: " + responseCode);
        if (responseCode == 200) {
            BufferedReader in = new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            return response.toString();
        }
        System.out.println("GET request not worked");
        return new String("");
    }

    private static String getHealthScore() throws IOException {
        String response = sendHttpGetRequestToURL(JOB_URL);
        try {
            Object jobj = (new JSONParser()).parse(response.toString());
            JSONObject jo = (JSONObject) jobj;
            String healthReport = jo.get("healthReport").toString();
            System.out.println(healthReport);
            JSONArray ja = (JSONArray) jo.get("healthReport");
            Iterator itr2 = ja.iterator();
            String jstr = new String();
            while (itr2.hasNext()) {
                jstr = itr2.next().toString();
                System.out.println(jstr);
            }
            Object jobj2 = (new JSONParser()).parse(jstr);
            JSONObject jo2 = (JSONObject) jobj2;
            return jo2.get("score").toString();
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return new String("");
        }
    }

    private static boolean isBuildBuilding(long buildNumber) {
        try {
            String buildURL = getBuildURL(buildNumber);
            String response = sendHttpGetRequestToURL(buildURL);
            Object jobj = (new JSONParser()).parse(response);
            JSONObject jo = (JSONObject) jobj;
            String vString = jo.get("building").toString();
            System.out.println(vString);
            if (vString.equalsIgnoreCase("true")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return false;
        }
    }
    
    private static boolean isBuildSuccessful(long buildNumber) {
        try {
            String buildURL = getBuildURL(buildNumber);
            String response = sendHttpGetRequestToURL(buildURL);
            Object jobj = (new JSONParser()).parse(response);
            JSONObject jo = (JSONObject) jobj;
            String vString = jo.get("result").toString();
            System.out.println(vString);
            if (vString.equalsIgnoreCase("SUCCESS")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return false;
        }
    }
    
    private static boolean isLastBuildBuilding() {
        try {
            String lastBuildURL = getLastBuildURL();
            String response = sendHttpGetRequestToURL(lastBuildURL);
            Object jobj = (new JSONParser()).parse(response);
            JSONObject jo = (JSONObject) jobj;
            String vString = jo.get("building").toString();
            System.out.println(vString);
            if (vString.equalsIgnoreCase("true")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return false;
        }
    }

    private static String sendHttpPostRequestToURL(String URLString, byte data[]) throws IOException {
        URL obj = new URL(URLString);
        HttpURLConnection.setFollowRedirects(false);
        HttpURLConnection httpURLConnection = (HttpURLConnection) obj.openConnection();
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setRequestProperty("User-Agent", USER_AGENT);
        byte[] authEncBytes = Base64.encodeBase64(USER_AND_PASSWORD.getBytes());
        String authStringEnc = new String(authEncBytes);
        httpURLConnection.setRequestProperty("Authorization", "Basic " + authStringEnc);
        httpURLConnection.setDoOutput(true);
        OutputStream os = httpURLConnection.getOutputStream();
        os.write(data);
        os.flush();
        os.close();
        int responseCode = httpURLConnection.getResponseCode();
        System.out.println("POST Response Code :: " + responseCode);
        if (responseCode == 200 || responseCode == 302) {
            BufferedReader in = new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream()));
            StringBuffer response = new StringBuffer();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            System.out.println(response.toString());
            return response.toString();
        } else {
            System.out.println("POST request not worked");
        }
        return "";
    }
    
    private static long triggerANewBuild() throws IOException {
        long ts = System.currentTimeMillis()/1000;
        sendHttpPostRequestToURL(NextBuildNumberURL, ("nextBuildNumber=" + String.valueOf(ts)).getBytes());
        sendHttpPostRequestToURL(POST_URL, POST_PARAM.getBytes());
        return ts;
    }

    @Test
    public void testWhenVmIsDown() throws Exception {
        int RUN_TIMES = 10;
        List<Long> buildNumbers;
        buildNumbers = new ArrayList<>();
        for (int i = 0; i < RUN_TIMES; i++) {
            long nextBuildNumber = triggerANewBuild();
            while (!isBuildBuilding(nextBuildNumber))
            {
                System.out.printf("Waiting the job %d to be started\n", nextBuildNumber);
                Thread.sleep(1000L);
            }
            buildNumbers.add(new Long(nextBuildNumber));
        }
        int timeElapsed = 0;
        while (isLastBuildBuilding()) {
            System.out.println("Building, let's wait one more second...");
            Thread.sleep(1000L);
            timeElapsed++;
            System.out.printf("%d seconds passed\n", new Object[]{Integer.valueOf(timeElapsed)});
        }
        
        int nSuccessful = 0;
        for (Long buildNumber : buildNumbers) {
            if (isBuildSuccessful(buildNumber.longValue()))
            {
                nSuccessful++;
            }
        }
        System.out.printf("Total: %d runs, %d of them are successful.\n", buildNumbers.size(), nSuccessful);
        Assert.assertTrue(nSuccessful == buildNumbers.size());
    } 
    
}
