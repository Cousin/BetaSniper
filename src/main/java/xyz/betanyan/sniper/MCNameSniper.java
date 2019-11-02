package xyz.betanyan.sniper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MCNameSniper {

    private static CopyOnWriteArrayList<SnipeProxy> proxies;

    private static boolean report;
    private static boolean showResponses;
    public static boolean stopMessages;

    private static long dropTime;

    private static String proxySource = "N/A";

    private static SniperConfig config;

    public static void main(String[] args) {

        Yaml yaml = new Yaml();
        try {
            config = new SniperConfig((Map<String, Map<String, String>>) yaml
                    .load(new FileReader(new File("config.yml"))));
        } catch (FileNotFoundException e) {
            System.out.println("You're missing your config.yml file!");
            return;
        }

        File proxyFile = new File("proxies.txt");
        if (!proxyFile.exists()) {
            try {
                proxyFile.createNewFile();
            } catch (IOException e) {
                System.out.println("Error creating proxy file! Check file permissions!");
                e.printStackTrace();
                return;
            }
        }

        boolean removeDupes = config.getOrDefault("remove-proxy-dupes", Boolean.class, true);

        if (!config.getOrDefault("use-proxy-source", Boolean.class, false)) {

            proxies = new CopyOnWriteArrayList<>();
            try {
                for (String line : Files.readAllLines(proxyFile.toPath())) {
                    if (line.contains(":")) {
                        try {
                            SnipeProxy proxy = new SnipeProxy(line.split(":")[0], Integer.parseInt(line.split(":")[1]));
                            SnipeProxy found = proxies.stream().filter(proxy::equals).findAny().orElse(null);

                            if (found == null) {
                                proxies.add(proxy);
                            } else {
                                if (!removeDupes) {
                                    proxies.add(proxy);
                                }
                            }
                        } catch (IllegalArgumentException x) {
                            continue;
                        }
                    }
                }
            } catch (IOException e) {
                proxies = new CopyOnWriteArrayList<>();
            }

        } else {

            proxySource = config.getOrDefault("proxy-source", String.class, "stop_decompiling_and_make_your_own_sniper");

        }
        Object[] data = new Object[6];
        int[] requests = new int[5];
        List<Integer> proxyRequests = new ArrayList<>(config.getOrDefault("proxy-requests", ArrayList.class, new ArrayList()).size());

        data[0] = config.get("account.email", String.class);
        data[1] = config.get("account.password", String.class);
        data[2] = config.get("account.wantedUsername", String.class);
        data[3] = config.get("account.uuid", String.class);
        data[4] = config.get("latency", Integer.class);

        report = config.getOrDefault("report-successful", Boolean.class, true);

        Iterator<Integer> reqIter = config.get("requests", ArrayList.class).iterator();

        for (int i=0;i<config.get("requests", ArrayList.class).size();i++) {
            if (reqIter.hasNext()) {
                requests[i] = reqIter.next();
            }
        }

        if (config.getOrDefault("use-proxies", Boolean.class, false)) {
            for (Object proxyRequest : config.get("proxy-requests", ArrayList.class)) {
                String req = String.valueOf(proxyRequest);
                if (req.startsWith("[") && req.endsWith("]")) {
                    int num1 = Integer.parseInt(req.split("\\[")[1].split(":")[0]);
                    int num2 = Integer.parseInt(req.split("]")[0].split(":")[1]);
                    for (int i=num1;i>=num2;i--) {
                        proxyRequests.add(i);
                    }
                } else {
                    proxyRequests.add(Integer.parseInt(proxyRequest.toString()));
                }
            }
        } else {
            proxyRequests.add(0, -8008);
        }

        if (config.getOrDefault("use-proxies", Boolean.class, false)) {
            if (proxyRequests.size() > proxies.size()) {
                System.out.println("You can not have more proxy requests than proxies!");
                System.out.println("Requests: " + proxyRequests.size());
                System.out.println("Proxies: " + proxies.size());
                if (removeDupes) {
                    System.out.println("Remember BetaSniper removes proxies with the same IP!");
                }
                System.exit(0);
            }
        }

        showResponses = config.getOrDefault("show-concurrent-messages", Boolean.class, true);

        boolean usingSecurity = false;
        String[] answers = new String[3];

        if (config.getOrDefault("security_questions.use", Boolean.class, false)) {
            usingSecurity = true;
            answers[0] = config.getOrDefault("security_questions.answer1", String.class, "stop_decompiling_and_make_your_own_sniper");
            answers[1] = config.getOrDefault("security_questions.answer2", String.class, "stop_decompiling_and_make_your_own_sniper");
            answers[2] = config.getOrDefault("security_questions.answer3", String.class, "stop_decompiling_and_make_your_own_sniper");
        }

        // My way of getting the timestamp that the name drops. Could maybe be simplified, it was hard to get it to work with almost any case.
        ConnectionBuilder grabUUID = new ConnectionBuilder(
                String.format("https://api.mojang.com/users/profiles/minecraft/%s?at=" + ((System.currentTimeMillis() - TimeUnit.DAYS.toMillis(37)) / 1000), data[2]));
        grabUUID.method("GET");
        grabUUID.send();
        String grabResponse = grabUUID.getResponse();
        String wantedUuid;
        try {
            wantedUuid = grabResponse.split("\",\"name\":")[0].split("\\{\"id\":\"")[1];
        } catch (ArrayIndexOutOfBoundsException x) {
            System.out.println("This account has already dropped!");
            return;
        }

        ConnectionBuilder nameHistory = new ConnectionBuilder(
                String.format("https://api.mojang.com/user/profiles/%s/names", wantedUuid)
        );
        nameHistory.method("GET");
        nameHistory.send();

        String nameHistoryResponse = nameHistory.getResponse();

        int foundIndex = 0;
        int index = 0;
        JSONArray historyJson = new JSONArray(nameHistoryResponse);
        for (Object obj : historyJson) {
            index++;
            JSONObject jsonObj = (JSONObject) obj;
            String name = jsonObj.getString("name");
            if (name.equalsIgnoreCase(data[2].toString())) {
                foundIndex = index;
            }
        }

        try {
            dropTime = ((JSONObject) historyJson.get(foundIndex)).getLong("changedToAt") + TimeUnit.DAYS.toMillis(37);
        } catch (JSONException x) {
            System.out.println("This name is not dropping!");
            return;
        }

        long diff = Long.parseLong(String.valueOf(config.get("clock-time-difference", Integer.class)));
        long snipeTime = dropTime - diff;

        if (snipeTime - System.currentTimeMillis() <= 0) {
            System.out.println("This name has already dropped!");
        } else {
            try {
                System.out.println("Dropping in: " + millisFormatted(snipeTime - System.currentTimeMillis()));
                System.out.println("Starting snipe.");
                if (!proxyRequests.get(0).equals(-8008)) {
                    System.out.println("Loaded " + proxies.size() + " proxies.");
                    System.out.println("Using " + proxyRequests.size() + " proxies.");
                }
                new MCNameSniper(
                        (String) data[0],
                        (String) data[1],
                        (String) data[2],
                        snipeTime,
                        (int) data[4],
                        requests, proxyRequests,
                        usingSecurity, answers, config);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    private static int b(int l) {
        return (ThreadLocalRandom.current().nextInt(1) + l) - y();
    }

    public MCNameSniper(String email, String password, String wantedUsername, long time, int latency,
                        int[] requests, List<Integer> proxyRequests, boolean seqQuestions, String[] answers, SniperConfig config)
        throws IOException {

        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> headers = new AtomicReference<>();
        AtomicReference<String> uuid = new AtomicReference<>();

        new Thread(() -> {
            boolean lock = true;
            while (lock) {
                if (time - System.currentTimeMillis() <= 60_000) {
                    LoginResult loginResults = login(email, password, seqQuestions, answers);
                    if (loginResults.authToken.equals("invalid_security")) {
                        System.exit(-1);
                    } else if (loginResults.authToken.equals("bad_code") || loginResults.authToken.equals("bad_redirect")) {
                        try {
                            Thread.sleep(7_500);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else {

                        ConnectionBuilder proxySourceGet = new ConnectionBuilder(proxySource)
                                .https(proxySource.startsWith("https"))
                                .method("GET").send();

                        String response = proxySourceGet.getResponse();

                        proxies = new CopyOnWriteArrayList<>();
                        boolean removeDupes = config.getOrDefault("remove-proxy-dupes", Boolean.class, true);

                        for (String line : response.split("\n")) {
                            if (line.contains(":")) {
                                try {
                                    SnipeProxy proxy = new SnipeProxy(line.split(":")[0], Integer.parseInt(line.split(":")[1]));
                                    SnipeProxy found = proxies.stream().filter(proxy::equals).findAny().orElse(null);

                                    if (found == null) {
                                        proxies.add(proxy);
                                    } else {
                                        if (!removeDupes) {
                                            proxies.add(proxy);
                                        }
                                    }
                                } catch (IllegalArgumentException x) {
                                    continue;
                                }
                            }
                        }

                        token.set(loginResults.authToken);
                        headers.set(loginResults.headers);
                        uuid.set(loginResults.uuid);

                        lock = false;
                    }
                }
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        int dropTimeTracker = config.getOrDefault("drop-time-tracker", Integer.class, 15);

        AtomicBoolean checked = new AtomicBoolean(false);

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                long current = System.currentTimeMillis();
                long seconds = millisSeconds(time - current);

                if (seconds > -10) {

                    if (seconds % dropTimeTracker == 0) {
                        System.out.println("Dropping in: " + millisFormatted(time - current));
                    }

                } else {

                    if (!checked.get()) {

                        checked.set(true);
                        stopMessages = true;
                        System.out.println("All requests finished! Checking if you got the name...");

                        ConnectionBuilder getName = new ConnectionBuilder(
                                String.format("https://api.mojang.com/user/profiles/%s/names", uuid)).send();

                        String resp = getName.getResponse();
                        if (resp.startsWith("[")) {
                            JSONArray historyJson = new JSONArray(resp);
                            String name = ((JSONObject) historyJson.get(historyJson.length() - 1)).getString("name");
                            if (name.equalsIgnoreCase(wantedUsername)) {
                                System.out.println(parseOutput("Name changed", wantedUsername));
                                System.exit(0);
                            } else {
                                System.out.println("Sadly, you did not get the name.");
                                System.exit(0);
                            }
                        } else {
                            System.out.println("Sadly, you did not get the name.");
                            System.exit(0);
                        }
                    }

                }
            }
        }, 0, 1000);

        for (int requestTime : requests) {

            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {

                    new Thread(() -> {
                        if (!stopMessages) {
                            System.out.println("Attempting name change");
                            try {
                                sendNameChangeRequest(headers.get(), token.get(), wantedUsername, uuid.get(), password, null);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }).start();

                }
            }, ((time - System.currentTimeMillis()) - latency) - requestTime);

        }

        if (proxyRequests.get(0) != -8008) {

            List<Integer> usedRequests = new ArrayList<>();

            List<CopyOnWriteArrayList<Integer>> subLists = new ArrayList<>();

            int size = proxyRequests.size();

            int maxSplitSize = config.getOrDefault("max-proxy-thread-split", Integer.class, 500);
            int reuseAmount = config.getOrDefault("proxy-reuse-amount", Integer.class, 5);

            boolean multiThreadReUsed = config.getOrDefault("reuse-proxy-multithread", Boolean.class, false);

            if (size > maxSplitSize) {

                int nextStart = 0;

                int divis = getDivisibleAmount(size, 10);
                int wat = size / getDivisibleAmount(size, 10);

                for (int i = 0; i < wat; i++) {
                    subLists.add(new CopyOnWriteArrayList<>(
                            proxyRequests.subList(nextStart, nextStart + divis)
                    ));
                    nextStart += divis;
                }

                if (size % wat != 0) {

                    subLists.add(new CopyOnWriteArrayList<>(
                            proxyRequests.subList(nextStart, size)
                    ));

                }

            }

            ExecutorService service = Executors.newCachedThreadPool();
            ScheduledExecutorService timeService = Executors.newSingleThreadScheduledExecutor();

            long initialTime = ((time - System.currentTimeMillis()) - latency);

            timeService.scheduleAtFixedRate(() -> {
                if (!stopMessages) {

                    if (proxyRequests.size() <= maxSplitSize) {

                        for (int requestTime : proxyRequests) {

                            long timeLeft = ((time - System.currentTimeMillis()) - latency);

                            if (timeLeft - requestTime <= requestTime) {

                                if (!usedRequests.contains(requestTime)) {

                                    usedRequests.add(requestTime);

                                    if (!stopMessages) {

                                        System.out.println("Attempting proxy name change");

                                        service.submit(new Thread(() -> {
                                            SnipeProxy snipeProxy = getProxy();
                                            Proxy proxy;
                                            if (snipeProxy != null) {
                                                proxy = snipeProxy.proxy;
                                            } else {
                                                proxy = null;
                                            }
                                            try {
                                                for (int i = 0; i < reuseAmount; i++) {
                                                    if (multiThreadReUsed) {
                                                        service.submit(new Thread(() -> {
                                                            try {
                                                                sendNameChangeRequest(headers.get(), token.get(), wantedUsername, uuid.get(), password, proxy);
                                                            } catch (IOException e) {
                                                                e.printStackTrace();
                                                            }
                                                        }));
                                                    } else {
                                                        sendNameChangeRequest(headers.get(), token.get(), wantedUsername, uuid.get(), password, proxy);
                                                    }
                                                }
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                        }));

                                    }

                                }

                            }

                        }

                    } else {

                        for (CopyOnWriteArrayList<Integer> subListTimes : subLists) {

                            for (int requestTime : subListTimes) {

                                long timeLeft = ((time - System.currentTimeMillis()) - latency);

                                if (timeLeft + requestTime <= requestTime) {

                                    if (!usedRequests.contains(requestTime)) {

                                        usedRequests.add(requestTime);

                                        if (!stopMessages) {
                                            System.out.println("Attempting proxy name change");

                                            service.submit(new Thread(() -> {
                                                SnipeProxy snipeProxy = getProxy();
                                                Proxy proxy;
                                                if (snipeProxy != null) {
                                                    proxy = snipeProxy.proxy;
                                                } else {
                                                    proxy = null;
                                                }
                                                try {
                                                    for (int i = 0; i < reuseAmount; i++) {
                                                        if (multiThreadReUsed) {
                                                            service.submit(new Thread(() -> {
                                                                try {
                                                                    sendNameChangeRequest(headers.get(), token.get(), wantedUsername, uuid.get(), password, proxy);
                                                                } catch (IOException e) {
                                                                    e.printStackTrace();
                                                                }
                                                            }));
                                                        } else {
                                                            sendNameChangeRequest(headers.get(), token.get(), wantedUsername, uuid.get(), password, proxy);
                                                        }
                                                    }
                                                } catch (IOException e) {
                                                    e.printStackTrace();
                                                }
                                            }));

                                        }

                                    }

                                }

                            }

                        }

                    }

                }

            }, 0, 1, TimeUnit.MILLISECONDS);

        }

    }

    private int getDivisibleAmount(int proxySize, int toDivide) {
        if (proxySize - toDivide < 10) {
            return getDivisibleAmount(proxySize, toDivide - 1);
        }
        return toDivide;
    }

    private SnipeProxy getProxy() {
        try {
            SnipeProxy proxy = proxies.get(
                    proxies.size() - 1
            );

            proxies.remove(proxy);

            return proxy;
        } catch (ArrayIndexOutOfBoundsException x) {
            return null;
        }

    }

    private void sendNameChangeRequest(String headers, String authToken, String newName, String uuid, String password, Proxy proxy)
        throws IOException {

        if (!stopMessages) {

            String data = String.format("authenticityToken=%s&authenticityToken=%s&newName=%s&password=%s",
                    authToken, authToken, newName, password);

            ConnectionBuilder builder = new ConnectionBuilder("https://account.mojang.com/me/renameProfile/" + uuid)
                    .https(true)
                    .method("POST")
                    .header("Cookie", headers)
                    .header("Referer", "https://account.mojang.com/me/renameProfile/" + uuid)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .data(data);

            if (proxy != null)
                builder.proxy(proxy);

            builder.send();

            if (showResponses) {

                String response = builder.getResponse();

                if (!stopMessages) {
                    if (response.startsWith("Name changed")) {
                        if (!stopMessages) {
                            System.out.println("Response Code: " + builder.getResponseCode() +
                                    "\nRESULT: " + parseOutput(response, newName) + (proxy != null ? " Using Proxy " + proxy : ""));
                        }
                        if (report) {
                            if (!stopMessages) {
                                stopMessages = true;
                                System.out.println("Saving results to recent snipes page.");

                                ConnectionBuilder submitRecent = new ConnectionBuilder("https://betasniper.co/submitrecent.php")
                                        .method("POST").header("Content-Type", "application/x-www-form-urlencoded").data(String.format("license=%s&sniped=%s&date=%s&password=iue78y543u5784935u4h5u48g5768",
                                                config.get("license", String.class), newName, dropTime / 1000)
                                        ).send();

                                submitRecent.getResponseCode();

                            }
                        }
                        stopMessages = true;
                        System.exit(0);
                    } else if (response.contains("{\"error\":\"Invalid name change:")) {

                        stopMessages = true;
                        System.out.println("You may have got the name! Verifying to see if you really did...");

                        ConnectionBuilder getName = new ConnectionBuilder(
                                String.format("https://api.mojang.com/user/profiles/%s/names", uuid));

                        try {
                            JSONArray historyJson = new JSONArray(getName.getResponse());
                            String name = ((JSONObject) historyJson.get(historyJson.length() - 1)).getString("name");
                            if (name.equalsIgnoreCase(newName)) {
                                System.out.println(parseOutput("Name changed", newName));
                                System.exit(0);
                            } else {
                                System.out.println("Sadly, someone sniped the name millisecond(s) before you.");
                                System.exit(0);
                            }
                        } catch (JSONException x) {
                            System.out.println("Could not verify if you got the name, you may have got it though!");
                            System.exit(0);
                        }

                    }
                }

            } else {
                builder.getResponseCode();
            }

        }

    }

    private static int y() {
        return 4;
    }

    private LoginResult login(final String email, final String password, boolean seqQuestions, String... answers) {

        System.out.println("Logging into Mojang...");

        ConnectionBuilder loginInit = new ConnectionBuilder("https://account.mojang.com/login");
        loginInit.https(true);
        loginInit.method("GET");
        loginInit.send();

        String headerCookieInit = loginInit.getHeader("SET-COOKIE");
        String authTokenInit = headerCookieInit.split("___AT=")[1].split("&___ID=")[0];

        ConnectionBuilder loginConnection = new ConnectionBuilder("https://account.mojang.com/login");
        loginConnection.https(true);
        loginConnection.method("POST");
        String data = "authenticityToken=" + authTokenInit + "&username=" + urlEncode(email) + "&password=" + urlEncode(password);
        loginConnection.header("Host", "account.mojang.com");
        loginConnection.header("Accept", "*/*");
        loginConnection.header("Content-Type", "application/x-www-form-urlencoded");
        loginConnection.header("Content-Length", String.valueOf(data.length()));
        loginConnection.data(data);
        loginConnection.send();

        if (loginConnection.getResponseCode() != 302) {
            System.out.println("Could not login, trying again.");
            return new LoginResult("bad_code", "", "");
        }

        if (loginConnection.getHeader("Location").contains("/login")) {
            System.out.println("Could not login, trying again.");
            return new LoginResult("bad_redirect", "", "");
        }

        Map<String, List<String>> headers = loginConnection.getFinalConnection().getHeaderFields();
        String cookies = "";
        for (String entry : headers.get("Set-Cookie")) {
            cookies = cookies + entry + "; ";
        }

        if (seqQuestions) {
            System.out.println("Attempting to solve security questions...");
            ConnectionBuilder meGet = new ConnectionBuilder("https://account.mojang.com/me/challenge");
            meGet.https(true);
            meGet.method("GET");
            meGet.header("Cookie", cookies);
            meGet.send();

            headers = meGet.getFinalConnection().getHeaderFields();
            cookies = "";
            for (String entry : headers.get("Set-Cookie")) {
                cookies = cookies + entry + "; ";
            }

            String newAuthToken = cookies.split("___AT=")[1].split("&___ID=")[0];

            Document secDoc = Jsoup.parse(meGet.getResponse());
            String questionId0 = secDoc.getElementsByAttributeValue("name", "questionId0").get(0).attr("value");
            String questionId1 = secDoc.getElementsByAttributeValue("name", "questionId1").get(0).attr("value");
            String questionId2 = secDoc.getElementsByAttributeValue("name", "questionId2").get(0).attr("value");

            ConnectionBuilder challengePost = new ConnectionBuilder("https://account.mojang.com/me/completeChallenge");
            challengePost.https(true);
            challengePost.method("POST");
            challengePost.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            challengePost.header("Cookie", cookies);
            challengePost.data(String.format(
                    "authenticityToken=%s&answer0=%s&questionId0=%s&answer1=%s&questionId1=%s&answer2=%s&questionId2=%s",
                    newAuthToken, answers[0], questionId0, answers[1], questionId1, answers[2], questionId2
            ));
            challengePost.send();

            String challengeResponse = challengePost.getResponse();
            if (challengeResponse.contains("error")) {
                System.out.println("The answers to your security questions are invalid.");
                return new LoginResult("invalid_security", "", "");
            }

            headers = challengePost.getFinalConnection().getHeaderFields();
            cookies = "";
            for (String entry : headers.get("Set-Cookie")) {
                cookies = cookies + entry + "; ";
            }

            System.out.println("Security questions solved!");
        }

        ConnectionBuilder getMeHeaders = new ConnectionBuilder("https://account.mojang.com/me/")
                .header("Cookie", cookies).send();

        cookies = "";
        headers = getMeHeaders.getFinalConnection().getHeaderFields();
        for (String entry : headers.get("Set-Cookie")) {
            cookies = cookies + entry + "; ";
        }

        String response = getMeHeaders.getResponse();

        String uuid = response.split("minecraftProfiles\\.push\\(\'")[1].split("\'\\);        var me = \\{")[0];

        ConnectionBuilder getRenameHeaders = new ConnectionBuilder("https://account.mojang.com/me/renameProfile/" + uuid)
                .header("Cookie", cookies).send();

        cookies = "";
        headers = getRenameHeaders.getFinalConnection().getHeaderFields();
        for (String entry : headers.get("Set-Cookie")) {
            cookies = cookies + entry + "; ";
        }

        System.out.println("Found UUID: " + uuid);

        String authToken = getRenameHeaders.getHeader("Set-Cookie").split("___AT=")[1].split("&___ID=")[0];

        System.out.println("Logged in");

        return new LoginResult(authToken, cookies, uuid);
    }


    private String urlEncode(String str) {
        try {
            return URLEncoder.encode(str, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private String parseOutput(String output, String newName) {
        if (output.equals("")) {
            return "Invalid request! Make sure account works, has a name change available, and UUID has no dashes.";
        } else if (output.contains("Bad authenticity token")) {
            return "Your account does not have a name change available!";
        } else if (output.contains("Name already taken")) {
            return "Name not available (yet?)";
        } else if (output.contains("already reserved")) {
            return "You tried to change but it's already being changed (you might get the name)";
        } else if (output.startsWith("Name changed")) {
            return "Congratulations, you have successfully sniped the username \"" + newName + "\"!";
        } else if (output.contains("CloudFront")) {
            return "Your proxy must be HTTPS only! (Not HTTP or SOCKS)";
        } else if (output.contains("{\"error\":\"Invalid name change:")) {
            return "Congratulations, you have successfully sniped the username \"" + newName + "\"!";
        } else if (output.contains("{\"error\":\"Too many recent name changes.\"}")) {
            return "Proxy ratelimited";
        } else if (output.contains("{\"error\":\"Too many failed attempts.\"}")) {
            return "Proxy ratelimited";
        } else {
            return output;
        }
    }

    private static String f(String a) {

        String[] m = new String[b(100)];
        for (int x=-3;x<b(-3 * m.length);x += 3) {
            m[x] = new String(
                    new Throwable("DECOMPILE ERROR").toString()
            );
        }

        String x = a;
        for (int i=0;i<((Integer.MAX_VALUE - 1) - (Integer.MAX_VALUE - 1)) + 7 + b(4);i++) {
            x = new String(Base64.getDecoder().decode(x.getBytes()));
        }

        return x;

    }

    private static String millisFormatted(long millis) {

        long secondsInMilli = 1000;
        long minutesInMilli = secondsInMilli * 60;
        long hoursInMilli = minutesInMilli * 60;
        long daysInMilli = hoursInMilli * 24;

        long elapsedDays = millis / daysInMilli;
        millis = millis % daysInMilli;

        long elapsedHours = millis / hoursInMilli;
        millis = millis % hoursInMilli;

        long elapsedMinutes = millis / minutesInMilli;
        millis = millis % minutesInMilli;

        long elapsedSeconds = millis / secondsInMilli;

        return String.format(
                "%d days %d hours %d minutes %d seconds",
                elapsedDays,
                elapsedHours, elapsedMinutes, elapsedSeconds);
    }

    private static long millisSeconds(long millis) {
        return TimeUnit.MILLISECONDS.toSeconds(millis);
    }

    private class LoginResult {

        private String authToken;
        private String headers;
        private String uuid;

        public LoginResult(String authToken, String headers, String uuid) {
            this.authToken = authToken;
            this.headers = headers;
            this.uuid = uuid;
        }
    }

    private static class SnipeProxy {

        private String ip;
        private int port;

        private Proxy proxy;

        public SnipeProxy(String ip, int port) {
            this.ip = ip;
            this.port = port;

            this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ip, port));
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SnipeProxy) {
                SnipeProxy sP = (SnipeProxy) obj;
                if (sP.ip.equalsIgnoreCase(ip)) {
                    return true;
                }
            }
            return false;
        }

    }

}
