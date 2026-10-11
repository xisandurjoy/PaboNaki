package com.pabonaki.integration;

import com.pabonaki.model.Order;
import com.pabonaki.model.Product;
import com.pabonaki.repo.OrderRepository;
import com.pabonaki.repo.ProductRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class IntegrationService {
    private final RestClient http = RestClient.builder().build();
    private final ProductRepository products;
    private final OrderRepository orders;
    private final MongoTemplate mongo;
    private final String publicBaseUrl, geminiKey, geminiModel, googleClientId, resendKey, emailFrom;
    private final String brevoKey, brevoSenderEmail, brevoSenderName;
    private final String cloudName, cloudKey, cloudSecret;
    private final String pathaoBase, pathaoClientId, pathaoClientSecret, pathaoUsername, pathaoPassword, pathaoStoreId, pathaoCityId, pathaoZoneId, pathaoAreaId;
    private final String sslBase, sslStoreId, sslStorePassword;

    public IntegrationService(ProductRepository products, OrderRepository orders, MongoTemplate mongo,
        @Value("${app.public-base-url}") String publicBaseUrl,
        @Value("${app.integrations.gemini-key}") String geminiKey,
        @Value("${app.integrations.gemini-model}") String geminiModel,
        @Value("${app.integrations.google-client-id}") String googleClientId,
        @Value("${app.integrations.resend-key}") String resendKey,
        @Value("${app.integrations.email-from}") String emailFrom,
        @Value("${app.integrations.brevo-key:}") String brevoKey,
        @Value("${app.integrations.brevo-sender-email:}") String brevoSenderEmail,
        @Value("${app.integrations.brevo-sender-name:}") String brevoSenderName,
        @Value("${app.integrations.cloudinary-cloud-name}") String cloudName,
        @Value("${app.integrations.cloudinary-api-key}") String cloudKey,
        @Value("${app.integrations.cloudinary-api-secret}") String cloudSecret,
        @Value("${app.integrations.pathao-base-url}") String pathaoBase,
        @Value("${app.integrations.pathao-client-id}") String pathaoClientId,
        @Value("${app.integrations.pathao-client-secret}") String pathaoClientSecret,
        @Value("${app.integrations.pathao-username}") String pathaoUsername,
        @Value("${app.integrations.pathao-password}") String pathaoPassword,
        @Value("${app.integrations.pathao-store-id}") String pathaoStoreId,
        @Value("${app.integrations.pathao-city-id}") String pathaoCityId, @Value("${app.integrations.pathao-zone-id}") String pathaoZoneId, @Value("${app.integrations.pathao-area-id}") String pathaoAreaId,
        @Value("${app.integrations.sslcommerz-base-url}") String sslBase,
        @Value("${app.integrations.sslcommerz-store-id}") String sslStoreId,
        @Value("${app.integrations.sslcommerz-store-password}") String sslStorePassword) {
        this.products=products; this.orders=orders; this.mongo=mongo; this.publicBaseUrl=publicBaseUrl; this.geminiKey=geminiKey; this.geminiModel=geminiModel;
        this.googleClientId=googleClientId; this.resendKey=resendKey; this.emailFrom=emailFrom;
        this.brevoKey=brevoKey; this.brevoSenderEmail=brevoSenderEmail; this.brevoSenderName=brevoSenderName;
        this.cloudName=cloudName; this.cloudKey=cloudKey; this.cloudSecret=cloudSecret;
        this.pathaoBase=pathaoBase; this.pathaoClientId=pathaoClientId; this.pathaoClientSecret=pathaoClientSecret; this.pathaoUsername=pathaoUsername; this.pathaoPassword=pathaoPassword; this.pathaoStoreId=pathaoStoreId; this.pathaoCityId=pathaoCityId; this.pathaoZoneId=pathaoZoneId; this.pathaoAreaId=pathaoAreaId;
        this.sslBase=sslBase; this.sslStoreId=sslStoreId; this.sslStorePassword=sslStorePassword;
    }

    public String googleClientId(){return googleClientId;}
    public boolean emailConfigured(){
        return configured(brevoKey,brevoSenderEmail) || configured(resendKey,emailFrom);
    }

    private boolean configured(String... values){ for(String v:values) if(v==null||v.isBlank()) return false; return true; }
    public Map<String,Object> integrationStatus(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("SSLCOMMERZ",Map.of("configured",configured(sslStoreId,sslStorePassword),"mode",sslBase.contains("sandbox")?"SANDBOX":"LIVE","purpose","Online checkout and payment validation"));
        out.put("Gemini AI",Map.of("configured",configured(geminiKey),"purpose","Product discovery assistant"));
        out.put("Google Sign-In",Map.of("configured",configured(googleClientId),"purpose","Customer authentication"));
        out.put("Cloudinary",Map.of("configured",configured(cloudName,cloudKey,cloudSecret),"purpose","Product image upload"));
        out.put("Email (Brevo API)",Map.of("configured",configured(brevoKey,brevoSenderEmail),"purpose","Registration OTP, password reset, order and support emails"));
        out.put("Email (Resend API fallback)",Map.of("configured",configured(resendKey,emailFrom),"purpose","Fallback delivery when Brevo is not configured"));
        out.put("Pathao Courier",Map.of("configured",configured(pathaoClientId,pathaoClientSecret,pathaoUsername,pathaoPassword,pathaoStoreId,pathaoCityId,pathaoZoneId,pathaoAreaId),"purpose","Shipment booking and tracking"));
        return out;
    }

    public Map<String,Object> askGemini(String message) {
        require(geminiKey,"GEMINI_API_KEY");
        String catalog=products.findByStatusOrderByCreatedAtDesc("ACTIVE").stream().limit(80)
            .map(p -> p.getName()+" | category="+p.getCategory()+" | price BDT="+p.getPrice()+" | stock="+p.getStock())
            .reduce("",(a,b)->a+"\n"+b);
        Map<String,Object> body=Map.of("system_instruction",Map.of("parts",List.of(Map.of("text","You are PaboNaki's shopping assistant in Bangladesh. Recommend only items present in the catalog below. Never invent prices or stock. If nothing matches, say so. Keep answers concise.\nCATALOG:\n"+catalog))),"contents",List.of(Map.of("role","user","parts",List.of(Map.of("text",message)))));
        Map result=http.post().uri("https://generativelanguage.googleapis.com/v1beta/models/"+geminiModel+":generateContent?key="+geminiKey).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
        return Map.of("answer",extractGeminiText(result));
    }
    private String extractGeminiText(Map<?,?> result){try{List<?> c=(List<?>)result.get("candidates");Map<?,?> first=(Map<?,?>)c.get(0);Map<?,?> content=(Map<?,?>)first.get("content");List<?> parts=(List<?>)content.get("parts");return String.valueOf(((Map<?,?>)parts.get(0)).get("text"));}catch(Exception e){throw new IllegalStateException("Gemini returned no answer");}}

    public Map<String,Object> verifyGoogleToken(String idToken) {
        require(googleClientId,"GOOGLE_CLIENT_ID");
        if (idToken == null || idToken.isBlank()) throw new IllegalArgumentException("Google ID token is missing");
        Map result=http.get().uri("https://oauth2.googleapis.com/tokeninfo?id_token="+java.net.URLEncoder.encode(idToken, java.nio.charset.StandardCharsets.UTF_8)).retrieve().body(Map.class);
        if(result==null || !googleClientId.equals(result.get("aud")) || !"true".equals(String.valueOf(result.get("email_verified")))) throw new IllegalArgumentException("Google token is invalid or email is not verified");
        return result;
    }

    public void notifyOrder(Order order) {
        String html="<h2>Thank you for shopping with PaboNaki!</h2><p>Your order <b>"+escapeHtml(order.getOrderNumber())+"</b> has been received.</p><p>Total: BDT "+String.format(Locale.US,"%.2f",order.getTotal())+"</p><p>Payment: "+escapeHtml(order.getPaymentMethod())+" ("+escapeHtml(order.getPaymentStatus())+")</p><p>Status: "+escapeHtml(order.getStatus())+"</p>";
        final String to=order.getEmail(),subject="PaboNaki order "+order.getOrderNumber()+" received",number=order.getOrderNumber();
        java.util.concurrent.CompletableFuture.runAsync(()->{ try { sendEmail(to,subject,html); } catch(RuntimeException ex) { System.err.println("Order email failed for "+number+": "+ex.getMessage()); } });
    }
    public void notifyOrderStatus(Order order) {
        String html="<div style='font-family:Arial,sans-serif;max-width:560px;margin:auto;padding:24px;color:#211044'><h2>Your PaboNaki order has been updated</h2><p>Hello "+escapeHtml(order.getCustomerName())+",</p><p>Order <b>"+escapeHtml(order.getOrderNumber())+"</b> status is now:</p><p style='display:inline-block;padding:10px 16px;border-radius:12px;background:#f0e8ff;color:#5c35bd;font-weight:bold'>"+escapeHtml(order.getStatus().replace('_',' '))+"</p><p>Total: BDT "+String.format(Locale.US,"%.2f",order.getTotal())+"</p><p>Thank you for shopping with PaboNaki.</p></div>";
        final String to=order.getEmail(),subject="PaboNaki order update: "+order.getOrderNumber(),number=order.getOrderNumber();
        java.util.concurrent.CompletableFuture.runAsync(()->{ try { sendEmail(to,subject,html); } catch(RuntimeException ex) { System.err.println("Order status email failed for "+number+": "+ex.getMessage()); } });
    }
    private String escapeHtml(String value){return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}

    public Map<String,Object> sendEmail(String to, String subject, String html) {
        if (configured(brevoKey, brevoSenderEmail)) return sendViaBrevo(to, subject, html);
        if (configured(resendKey, emailFrom)) return sendViaResend(to, subject, html);
        throw new IllegalStateException("Email is not configured. Set BREVO_API_KEY and BREVO_SENDER_EMAIL in the environment.");
    }

    private Map<String,Object> sendViaBrevo(String to, String subject, String html) {
        Map<String,Object> sender = new LinkedHashMap<>();
        sender.put("email", brevoSenderEmail);
        if (brevoSenderName != null && !brevoSenderName.isBlank()) sender.put("name", brevoSenderName);
        Map<String,Object> body = Map.of("sender", sender, "to", List.of(Map.of("email", to)),
            "subject", subject, "htmlContent", html);
        try {
            Map response = http.post().uri("https://api.brevo.com/v3/smtp/email")
                .header("api-key", brevoKey).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Map.class);
            return response == null ? Map.of("status", "accepted", "provider", "Brevo") : response;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            System.err.println("Brevo email API HTTP " + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString());
            throw new IllegalStateException("Brevo email API returned HTTP " + ex.getStatusCode().value()
                + ". Verify BREVO_API_KEY and that BREVO_SENDER_EMAIL is a verified sender.");
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            throw new IllegalStateException("Cannot reach Brevo email service. Check network connectivity.");
        }
    }

    private Map<String,Object> sendViaResend(String to, String subject, String html) {
        try {
            Map<String,Object> body = Map.of("from", emailFrom, "to", List.of(to), "subject", subject, "html", html);
            Map response = http.post().uri("https://api.resend.com/emails")
                .header("Authorization", "Bearer " + resendKey).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Map.class);
            return response == null ? Map.of("status", "accepted", "provider", "Resend") : response;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            System.err.println("Resend email API HTTP " + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString());
            throw new IllegalStateException("Resend email service returned HTTP " + ex.getStatusCode().value()
                + ". Verify RESEND_API_KEY and that EMAIL_FROM is an approved sender.");
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            throw new IllegalStateException("Cannot reach Resend email service. Check network connectivity.");
        }
    }

    public Map<String,Object> uploadCloudinary(MultipartFile file) throws Exception {
        require(cloudName,"CLOUDINARY_CLOUD_NAME"); require(cloudKey,"CLOUDINARY_API_KEY"); require(cloudSecret,"CLOUDINARY_API_SECRET");
        final String cn=cloudName.trim(), ck=cloudKey.trim(), cs=cloudSecret.trim();
        if ("root".equalsIgnoreCase(cn) || cn.contains(" ") || cn.contains("/")) {
            throw new IllegalStateException("Invalid CLOUDINARY_CLOUD_NAME. Copy the exact Cloud Name from Cloudinary Dashboard > API Keys; do not use 'Root', your username, API key, or URL.");
        }
        if(file.isEmpty() || file.getSize()>8*1024*1024) throw new IllegalArgumentException("Choose an image up to 8 MB");
        String ct=file.getContentType();
        if(ct==null || !ct.startsWith("image/")) throw new IllegalArgumentException("Only image files are allowed");
        String original=file.getOriginalFilename()==null?"upload":file.getOriginalFilename().replaceAll("[^A-Za-z0-9._-]","_");
        if(original.isBlank()) original="upload";
        final String filename=original.contains(".")?original:original+".jpg";
        String timestamp=String.valueOf(Instant.now().getEpochSecond());
        Map<String,String> params=new TreeMap<>(); params.put("folder","pabonaki/products"); params.put("timestamp",timestamp);
        String toSign=params.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).reduce((a,b)->a+"&"+b).orElse("");
        String signature=sha1(toSign+cs);
        org.springframework.http.HttpHeaders partHeaders=new org.springframework.http.HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(ct));
        org.springframework.http.HttpEntity<ByteArrayResource> filePart=new org.springframework.http.HttpEntity<>(new ByteArrayResource(file.getBytes()){@Override public String getFilename(){return filename;}},partHeaders);
        MultiValueMap<String,Object> form=new LinkedMultiValueMap<>();
        form.add("file",filePart);
        form.add("api_key",ck);form.add("timestamp",timestamp);form.add("folder","pabonaki/products");form.add("signature",signature);
        try {
            Map response=http.post().uri("https://api.cloudinary.com/v1_1/"+cn+"/image/upload").contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(Map.class);
            if (response == null || response.get("secure_url") == null) throw new IllegalStateException("Cloudinary upload returned no image URL.");
            return response;
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            String body=ex.getResponseBodyAsString();
            String detail=body;
            try { Map<?,?> m=new com.fasterxml.jackson.databind.ObjectMapper().readValue(body,Map.class); Object err=m.get("error"); if(err instanceof Map<?,?> em && em.get("message")!=null) detail=String.valueOf(em.get("message")); } catch(Exception ignore) {}
            System.err.println("Cloudinary HTTP "+ex.getStatusCode().value()+" for cloud '"+cn+"', file '"+filename+"' ("+ct+", "+file.getSize()+" bytes): "+body);
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                throw new IllegalStateException("Cloudinary rejected the credentials ("+detail+"). Verify CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET, then restart the backend.");
            }
            throw new IllegalStateException("Cloudinary upload failed (HTTP " + ex.getStatusCode().value() + "): " + detail);
        }
    }

    public Map<String,Object> createPathaoShipment(Order order) {
        require(pathaoClientId,"PATHAO_CLIENT_ID");require(pathaoClientSecret,"PATHAO_CLIENT_SECRET");require(pathaoUsername,"PATHAO_USERNAME");require(pathaoPassword,"PATHAO_PASSWORD");require(pathaoStoreId,"PATHAO_STORE_ID");require(pathaoCityId,"PATHAO_CITY_ID");require(pathaoZoneId,"PATHAO_ZONE_ID");require(pathaoAreaId,"PATHAO_AREA_ID");
        Map tokenBody=Map.of("client_id",pathaoClientId,"client_secret",pathaoClientSecret,"username",pathaoUsername,"password",pathaoPassword,"grant_type","password");
        Map token=http.post().uri(pathaoBase+"/aladdin/api/v1/issue-token").contentType(MediaType.APPLICATION_JSON).body(tokenBody).retrieve().body(Map.class);
        String access=String.valueOf(token.get("access_token"));
        Map<String,Object> recipient=Map.of("recipient_name",order.getCustomerName(),"recipient_phone",order.getPhone(),"recipient_address",order.getAddress(),"recipient_city",Integer.parseInt(pathaoCityId),"recipient_zone",Integer.parseInt(pathaoZoneId),"recipient_area",Integer.parseInt(pathaoAreaId));
        Map<String,Object> payload=new HashMap<>(recipient);payload.put("store_id",Integer.parseInt(pathaoStoreId));payload.put("merchant_order_id",order.getOrderNumber());payload.put("delivery_type",48);payload.put("item_type",2);payload.put("special_instruction","PaboNaki order "+order.getOrderNumber());payload.put("item_quantity",order.getItems().stream().mapToInt(Order.Item::getQuantity).sum());payload.put("item_weight",1);payload.put("amount_to_collect",order.getPaymentMethod().equals("COD")?(int)Math.round(order.getTotal()):0);payload.put("item_description",order.getItems().stream().map(Order.Item::getName).reduce((a,b)->a+", "+b).orElse("PaboNaki items"));
        Map response=http.post().uri(pathaoBase+"/aladdin/api/v1/orders").header("Authorization","Bearer "+access).contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(Map.class);
        return response==null?Map.of():response;
    }

    public Map<String,Object> initiateSslCommerz(Order order) {
        require(sslStoreId,"SSLCOMMERZ_STORE_ID");require(sslStorePassword,"SSLCOMMERZ_STORE_PASSWORD");
        String tran="PNPAY-"+UUID.randomUUID();order.setTransactionId(tran);orders.save(order);
        MultiValueMap<String,String> form=new LinkedMultiValueMap<>();form.add("store_id",sslStoreId);form.add("store_passwd",sslStorePassword);form.add("total_amount",String.format(Locale.US,"%.2f",order.getTotal()));form.add("currency","BDT");form.add("tran_id",tran);form.add("success_url",publicBaseUrl+"/api/v1/payments/sslcommerz/success");form.add("fail_url",publicBaseUrl+"/api/v1/payments/sslcommerz/fail");form.add("cancel_url",publicBaseUrl+"/api/v1/payments/sslcommerz/cancel");form.add("ipn_url",publicBaseUrl+"/api/v1/payments/sslcommerz/ipn");form.add("cus_name",order.getCustomerName());form.add("cus_email",order.getEmail());form.add("cus_add1",order.getAddress());form.add("cus_city",order.getDeliveryArea());form.add("cus_postcode","1200");form.add("cus_country","Bangladesh");form.add("cus_phone",order.getPhone());form.add("shipping_method","NO");form.add("product_name","PaboNaki order "+order.getOrderNumber());form.add("product_category","General");form.add("product_profile","general");
        Map response=http.post().uri(sslBase+"/gwprocess/v4/api.php").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        if(response==null || response.get("GatewayPageURL")==null)throw new IllegalStateException("SSLCOMMERZ did not return a payment URL: "+response);
        return Map.of("paymentUrl",response.get("GatewayPageURL"),"transactionId",tran,"orderNumber",order.getOrderNumber());
    }

    public boolean validateSslCommerz(String valId, String tranId, String callbackStatus, String callbackAmount) {
        require(sslStoreId,"SSLCOMMERZ_STORE_ID");
        require(sslStorePassword,"SSLCOMMERZ_STORE_PASSWORD");
        if (valId == null || valId.isBlank() || tranId == null || tranId.isBlank()) return false;

        // The browser callback is untrusted. Only the server-to-server validation response
        // from SSLCOMMERZ is authoritative.
        String validationUrl = UriComponentsBuilder.fromHttpUrl(sslBase + "/validator/api/validationserverAPI.php")
                .queryParam("val_id", valId)
                .queryParam("store_id", sslStoreId)
                .queryParam("store_passwd", sslStorePassword)
                .queryParam("v", "1")
                .queryParam("format", "json")
                .build().encode().toUriString();

        final Map validation;
        try {
            validation = http.get().uri(validationUrl).retrieve().body(Map.class);
        } catch (RuntimeException ex) {
            System.err.println("SSLCOMMERZ validation request failed: " + ex.getMessage());
            return false;
        }
        if (validation == null) return false;

        String status = String.valueOf(validation.get("status"));
        String validatedTran = String.valueOf(validation.get("tran_id"));
        if (!(status.equalsIgnoreCase("VALID") || status.equalsIgnoreCase("VALIDATED"))) return false;
        if (!tranId.equals(validatedTran)) return false;

        // Do not accept a callback claiming success when the gateway says otherwise.
        if (callbackStatus != null && !callbackStatus.isBlank()
                && !(callbackStatus.equalsIgnoreCase("VALID") || callbackStatus.equalsIgnoreCase("VALIDATED"))) return false;

        Order order = orders.findByTransactionId(tranId).orElse(null);
        if (order == null || !"ONLINE".equalsIgnoreCase(order.getPaymentMethod())) return false;
        if ("CANCELLED".equalsIgnoreCase(order.getStatus())) return false;

        try {
            double verifiedAmount = Double.parseDouble(String.valueOf(validation.get("amount")));
            if (!Double.isFinite(verifiedAmount) || Math.abs(verifiedAmount - order.getTotal()) > 0.02) return false;
            if (callbackAmount != null && !callbackAmount.isBlank()) {
                double reportedAmount = Double.parseDouble(callbackAmount);
                if (!Double.isFinite(reportedAmount) || Math.abs(reportedAmount - order.getTotal()) > 0.02) return false;
            }
        } catch (RuntimeException invalidAmount) {
            return false;
        }

        Object currency = validation.get("currency");
        if (currency == null || !"BDT".equalsIgnoreCase(String.valueOf(currency))) return false;
        Object validatedStoreId = validation.get("store_id");
        if (validatedStoreId != null && !sslStoreId.equals(String.valueOf(validatedStoreId))) return false;

        // Idempotency: repeated success/IPN callbacks must not send duplicate notifications
        // or change an already-cancelled order to PAID.
        if ("PAID".equalsIgnoreCase(order.getPaymentStatus())) return true;
        if (!"PENDING".equalsIgnoreCase(order.getStatus())) return false;

        Query pendingOnlineOrder = new Query(new Criteria().andOperator(
                Criteria.where("transactionId").is(tranId),
                Criteria.where("paymentMethod").is("ONLINE"),
                Criteria.where("paymentStatus").ne("PAID"),
                Criteria.where("status").is("PENDING"),
                Criteria.where("total").gte(order.getTotal() - 0.001).lte(order.getTotal() + 0.001)
        ));
        Update markPaid = new Update().set("paymentStatus", "PAID").set("updatedAt", Instant.now());
        org.springframework.data.mongodb.core.FindAndModifyOptions options =
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true);
        Order paidOrder = mongo.findAndModify(pendingOnlineOrder, markPaid, options, Order.class);
        if (paidOrder != null) {
            notifyOrder(paidOrder);
            return true;
        }

        // Another callback may have won the race; treat a verified already-paid order as success.
        Order latest = orders.findByTransactionId(tranId).orElse(null);
        return latest != null && "PAID".equalsIgnoreCase(latest.getPaymentStatus())
                && !"CANCELLED".equalsIgnoreCase(latest.getStatus());
    }
    private void require(String value,String key){if(value==null||value.isBlank())throw new IllegalStateException(key+" is not configured. Add it to your environment.");}
    private String sha1(String s)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();}




    public Map<String, Object> testPathaoAuthentication() {
        require(pathaoClientId, "PATHAO_CLIENT_ID");
        require(pathaoClientSecret, "PATHAO_CLIENT_SECRET");
        require(pathaoUsername, "PATHAO_USERNAME");
        require(pathaoPassword, "PATHAO_PASSWORD");

        Map<String, Object> body = Map.of(
                "client_id", pathaoClientId,
                "client_secret", pathaoClientSecret,
                "username", pathaoUsername,
                "password", pathaoPassword,
                "grant_type", "password"
        );

        Map<?, ?> response = http.post()
                .uri(pathaoBase + "/aladdin/api/v1/issue-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        boolean success = response != null
                && response.get("access_token") != null;

        return Map.of(
                "authenticated", success,
                "message", success
                        ? "Pathao authentication successful"
                        : "Authentication failed"
        );
    }

}
