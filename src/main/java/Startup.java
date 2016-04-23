import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import spark.servlet.SparkApplication;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;

import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.post;

public class Startup implements SparkApplication{

    // Create retrofit rest client for CaptainHook
    private CaptainHookApiService service = CaptainHookApiService.retrofit.create(CaptainHookApiService.class);
    private TokenService tokenService = TokenService.retrofit.create(TokenService.class);

    private byte[] Secret ={};
    private ArrayList<String> alerts = new ArrayList();
    private ArrayList<String> logs = new ArrayList();
    private static int port;
    public static void main(String [] args){
        if(args.length>0){
            port=Integer.parseInt(args[0]);
        }else{
            port=4567;
        }
        port(port);
        new Startup().init();
    }

    private void createAlertWebHook() {
        // Tell CaptainHook that we want to subscribe to Alerts
        Call getTokenCall=tokenService.createToken(new ClientCredentials("transitapi_prod_postman_client","wimt85!").getMap());
        getTokenCall.enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                System.out.println(response.code());
                Call createHookCall =  service.createHook(new Hook("http://localhost:4567/hooks/alerts", "Alerts webhook"),"Bearer "+((Token)response.body()).access_token);
                createHookCall.enqueue(new Callback() {
                    @Override
                    public void onResponse(Call call, Response response2) {
                        if(response2.isSuccessful()) {
                            System.out.println("Created Hook Successfully");
                            logs.add("Created Hook Successfully");
                        }
                        else {
                            System.out.println(response.message());
                            System.out.println("Something went wrong calling webhook setup. Response code: " + response2.code());
                            logs.add("Something went wrong calling webhook setup. Response code: " + response2.code());
                        }
                    }

                    @Override
                    public void onFailure(Call call, Throwable t) {
                        if(t.getCause() == null) {
                            logs.add("CreateHookCall onFailure called.");
                        } else {
                            logs.add(t.getCause().toString());
                            logs.add(t.getCause().getLocalizedMessage());
                            logs.add("Error occurred in calling Captain Hook.");
                        }
                    }
                });
            }

            @Override
            public void onFailure(Call call, Throwable t) {

            }
        });

    }

    private String encode(byte[] key, byte[] data) throws Exception {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(key, sha256_HMAC.getAlgorithm());
        sha256_HMAC.init(secret_key);
        return DatatypeConverter.printBase64Binary(sha256_HMAC.doFinal(data));
    }

    @Override
    public void init() {
        try {
            String http_platform_port = System.getenv("HTTP_PLATFORM_PORT");
            port(Integer.parseInt(http_platform_port));
        }
        catch (Exception e){
        }

        System.out.println("Startup class loaded.");
        logs.add("Startup class loaded.");

        // default get
        get("/", (req, res) -> "Test Webhook client api running.");

        // get logs
        get("/logs", (req, res) -> logs.toString());

        // get alerts
        get("/alerts", (req, res) -> alerts.toString());

        // This is the endpoint that the webhook will connect to
        post("/hooks/alerts", (req, res) -> {
            if(req.headers().contains("X-Hook-Secret")) {
                // store secret
                Secret = Base64.getDecoder().decode(req.headers("X-Hook-Secret"));
                res.header("X-Hook-Secret", req.headers("X-Hook-Secret"));
                res.status(200);
                logs.add("X-Hook-Secret received");
                logs.add(req.headers("X-Hook-Secret"));
            } else if(req.headers().contains("X-Hook-Signature")){
                String body=req.body();
                logs.add(body);
                String bodyHash = "";
                try {
                    bodyHash = encode(Secret, body.getBytes("UTF-8"));
                    logs.add(bodyHash);
                }
                catch (Exception e) {
                    logs.add("Exception occurred encoding hash: " + e.getStackTrace().toString());
                    res.status(500);
                    return res;
                }
                String otherBodyHash=req.headers("X-Hook-Signature");
                logs.add(otherBodyHash);

                if(bodyHash.equals(otherBodyHash)){
                    res.status(200);
                }else{
                    res.status(403);
                }
                System.out.println(bodyHash);
                System.out.println("Message is:\n"+body);

                alerts.add(body);
                logs.add("Alert received.");
            } else{
                res.status(403);
                logs.add("Access denied: X-Hook-Signature or X-Hook-Secret does not present in headers.");
            }
            return res;
        });

        // this could be in a post create webhook call
        createAlertWebHook();
    }
}
