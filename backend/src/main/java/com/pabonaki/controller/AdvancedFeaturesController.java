package com.pabonaki.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pabonaki.model.Order;
import com.pabonaki.model.Product;
import com.pabonaki.model.User;
import com.pabonaki.repo.OrderRepository;
import com.pabonaki.repo.ProductRepository;
import com.pabonaki.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Additive advanced features. Kept separate from the existing controllers to avoid
 * replacing the existing login, checkout, Google sign-in, courier or payment flows.
 */
@RestController
@RequestMapping("/api/v1")
public class AdvancedFeaturesController {
    private final ProductRepository products;
    private final OrderRepository orders;
    private final UserRepository users;
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.create();
    @Value("${app.integrations.gemini-key:}") private String geminiKey;

    public AdvancedFeaturesController(ProductRepository products, OrderRepository orders,
                                      UserRepository users, MongoTemplate mongo, ObjectMapper mapper) {
        this.products = products; this.orders = orders; this.users = users; this.mongo = mongo; this.mapper = mapper;
    }

    // 1) AI visual search: Gemini describes the uploaded image, then the catalogue is matched locally.
    @PostMapping(value="/visual-search", consumes="multipart/form-data")
    public Map<String,Object> visualSearch(@RequestParam("image") MultipartFile image) {
        if (image == null || image.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an image first.");
        if (image.getSize() > 5 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Image must be 5 MB or smaller.");
        String type = image.getContentType();
        if (type == null || !List.of("image/jpeg","image/png","image/webp").contains(type.toLowerCase(Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a JPG, PNG or WEBP image.");
        if (geminiKey == null || geminiKey.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI visual search is not configured. Set GEMINI_API_KEY.");
        try {
            Map<String,Object> inline = Map.of("mimeType",type,"data",Base64.getEncoder().encodeToString(image.getBytes()));
            Map<String,Object> imagePart = Map.of("inlineData",inline);
            Map<String,Object> textPart = Map.of("text","Describe this shopping product in 6 to 12 concise search keywords: product type, visible color, material, style and use. Return keywords only.");
            Map<String,Object> body = Map.of("contents",List.of(Map.of("parts",List.of(textPart,imagePart))));
            String response = http.post().uri("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="+geminiKey)
                    .header("Content-Type","application/json").body(body).retrieve().body(String.class);
            JsonNode root = mapper.readTree(response);
            String keywords = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("").trim();
            if (keywords.isBlank()) throw new IllegalStateException("AI could not describe this image.");
            Set<String> terms = Arrays.stream(keywords.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                    .filter(t -> t.length() > 2).collect(Collectors.toSet());
            List<Product> found = products.findByStatusOrderByCreatedAtDesc("ACTIVE").stream()
                    .filter(p -> p.getStock() > 0)
                    .map(p -> Map.entry(p, score(p, terms)))
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<Product,Integer>comparingByValue().reversed())
                    .limit(12).map(Map.Entry::getKey).toList();
            return Map.of("keywords",keywords,"count",found.size(),"products",found);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Visual search failed. Check the Gemini API key and try again."); }
    }

    private int score(Product p, Set<String> terms) {
        String text = String.join(" ", Objects.toString(p.getName(),""), Objects.toString(p.getCategory(),""),
                Objects.toString(p.getDescription(),"")).toLowerCase(Locale.ROOT);
        int score=0; for(String term:terms) if(text.contains(term)) score += term.length()>5 ? 3 : 1; return score;
    }

    // 2) Personal recommendations from the signed-in user's purchase history and category affinity.
    @GetMapping("/recommendations")
    public Map<String,Object> recommendations(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        List<Order> history = orders.findByUserIdOrderByCreatedAtDesc(userId);
        Set<String> bought = new HashSet<>();
        Map<String,Integer> categories = new HashMap<>();
        for (Order o : history) if (!"CANCELLED".equalsIgnoreCase(o.getStatus())) {
            if (o.getItems()!=null) for (Order.Item i : o.getItems()) {
                if (i.getProductId()!=null) bought.add(i.getProductId());
                Product p = i.getProductId()==null?null:products.findById(i.getProductId()).orElse(null);
                if(p!=null && p.getCategory()!=null) categories.merge(p.getCategory(), Math.max(1,i.getQuantity()), Integer::sum);
            }
        }
        List<Product> recs = products.findByStatusOrderByCreatedAtDesc("ACTIVE").stream()
                .filter(p -> p.getStock()>0 && !bought.contains(p.getId()))
                .sorted(Comparator.<Product>comparingInt(p -> categories.getOrDefault(p.getCategory(),0)).reversed()
                        .thenComparing(Product::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(12).toList();
        return Map.of("strategy",history.isEmpty()?"popular-and-in-stock":"purchase-category-affinity","products",recs);
    }

    // 3) Read-only admin risk dashboard: highlights anomalies for review, never declares fraud automatically.
    @GetMapping("/admin/safety/overview")
    public Map<String,Object> safetyOverview() {
        List<Order> all=orders.findAllByOrderByCreatedAtDesc();
        Instant since=Instant.now().minus(24,ChronoUnit.HOURS);
        List<Map<String,Object>> flagged=new ArrayList<>();
        for(Order o:all) {
            List<String> reasons=new ArrayList<>();
            if(o.getCreatedAt()!=null && o.getCreatedAt().isAfter(since)) {
                if(o.getTotal()<=0) reasons.add("Non-positive order total");
                if(o.getItems()==null || o.getItems().isEmpty()) reasons.add("Order has no items");
                if("ONLINE".equalsIgnoreCase(o.getPaymentMethod()) && "PAID".equalsIgnoreCase(o.getPaymentStatus())
                        && (o.getTransactionId()==null || o.getTransactionId().isBlank())) reasons.add("Paid online order has no transaction reference");
                if("PAID".equalsIgnoreCase(o.getPaymentStatus()) && "CANCELLED".equalsIgnoreCase(o.getStatus())) reasons.add("Cancelled order is marked paid; reconcile/refund");
                if(!reasons.isEmpty()) flagged.add(Map.of("orderId",Objects.toString(o.getId(),""),"orderNumber",Objects.toString(o.getOrderNumber(),""),"total",o.getTotal(),"paymentStatus",Objects.toString(o.getPaymentStatus(),""),"status",Objects.toString(o.getStatus(),""),"reasons",reasons));
            }
        }
        return Map.of("checkedOrders",all.size(),"flaggedLast24Hours",flagged.size(),"reviewRequired",flagged);
    }

    // 4) Lightweight trend forecast based on historical paid orders; estimates only, not an ML guarantee.
    @GetMapping("/admin/analytics/predictions")
    public Map<String,Object> predictions() {
        Instant now=Instant.now(), last7=now.minus(7,ChronoUnit.DAYS), prev7=now.minus(14,ChronoUnit.DAYS);
        List<Order> all=orders.findAllByOrderByCreatedAtDesc();
        double recent=0, previous=0; int recentCount=0, previousCount=0;
        Map<String,Integer> sold=new HashMap<>();
        for(Order o:all) {
            if(!"PAID".equalsIgnoreCase(o.getPaymentStatus()) && !"DELIVERED".equalsIgnoreCase(o.getStatus())) continue;
            Instant t=o.getCreatedAt(); if(t==null) continue;
            if(t.isAfter(last7)){recent+=o.getTotal();recentCount++;}
            else if(t.isAfter(prev7)){previous+=o.getTotal();previousCount++;}
            if(o.getItems()!=null) for(Order.Item i:o.getItems()) if(i.getProductId()!=null) sold.merge(i.getProductId(),i.getQuantity(),Integer::sum);
        }
        List<Map<String,Object>> stockRisk=new ArrayList<>();
        for(Product p:products.findByStatusOrderByCreatedAtDesc("ACTIVE")) {
            int units=sold.getOrDefault(p.getId(),0);
            if(p.getStock()<=10 || (units>=3 && p.getStock()<=units)) {
                stockRisk.add(Map.of("productId",Objects.toString(p.getId(),""),"name",Objects.toString(p.getName(),""),"stock",p.getStock(),"unitsSoldInAvailableHistory",units,"risk",p.getStock()<=3?"HIGH":"MEDIUM"));
            }
        }
        double forecast=recentCount==0?0:Math.round((recent/7.0)*30*100.0)/100.0;
        double change=previous==0?(recent>0?100:0):Math.round(((recent-previous)/previous)*10000.0)/100.0;
        return Map.of("method","paid/delivered order trend; simple 30-day estimate","recent7DayRevenue",Math.round(recent*100.0)/100.0,
                "previous7DayRevenue",Math.round(previous*100.0)/100.0,"revenueChangePercent",change,
                "estimatedNext30DayRevenue",forecast,"stockRisk",stockRisk);
    }

    // 5) Loyalty: one point per Tk 100 in paid/delivered orders; referral rewards unlock only
    // after the referred customer has a paid/delivered order.
    @GetMapping("/loyalty/me")
    public Map<String,Object> loyalty(Authentication auth) {
        String uid=(String)auth.getPrincipal();
        User user=users.findById(uid).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found"));
        List<Order> mine=orders.findByUserIdOrderByCreatedAtDesc(uid);
        long points=mine.stream().filter(this::qualifies).mapToLong(o->(long)Math.floor(o.getTotal()/100.0)).sum();
        String code=referralCode(uid);
        long referrals=mongo.count(Query.query(Criteria.where("referrerUserId").is(uid)), ReferralLink.class);
        long successful=mongo.find(Query.query(Criteria.where("referrerUserId").is(uid)),ReferralLink.class).stream()
                .filter(r -> hasQualifyingOrder(r.getReferredUserId())).count();
        points += successful*200;
        return Map.of("points",points,"pointsRule","1 point per Tk 100 in paid/delivered orders","referralCode",code,
                "referralBonusPoints",200,"totalReferrals",referrals,"successfulReferrals",successful,"email",user.getEmail());
    }

    @PostMapping("/loyalty/referral/apply")
    public Map<String,Object> applyReferral(Authentication auth,@RequestBody Map<String,String> body) {
        String uid=(String)auth.getPrincipal();
        String code=body==null?"":Objects.toString(body.get("code"),"").trim().toUpperCase(Locale.ROOT);
        if(code.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a referral code.");
        User referrer=users.findAll().stream().filter(u->referralCode(u.getId()).equals(code)).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Referral code not found."));
        if(uid.equals(referrer.getId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"You cannot use your own referral code.");
        if(mongo.exists(Query.query(Criteria.where("referredUserId").is(uid)),ReferralLink.class))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A referral code has already been applied to this account.");
        ReferralLink link=new ReferralLink();link.setReferredUserId(uid);link.setReferrerUserId(referrer.getId());link.setAppliedAt(Instant.now());
        mongo.save(link);
        return Map.of("message","Referral code applied. Referral bonus is unlocked after a qualifying order.","bonusPoints",200);
    }

    private boolean qualifies(Order o){return "PAID".equalsIgnoreCase(o.getPaymentStatus()) || "DELIVERED".equalsIgnoreCase(o.getStatus());}
    private boolean hasQualifyingOrder(String uid){return orders.findByUserIdOrderByCreatedAtDesc(uid).stream().anyMatch(this::qualifies);}
    private String referralCode(String uid){return "PN"+Objects.toString(uid,"").replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT).substring(0,Math.min(8,Objects.toString(uid,"").replaceAll("[^A-Za-z0-9]","").length()));}

    @Document("pabonaki_referrals")
    public static class ReferralLink {
        @Id private String id;
        @Indexed(unique=true) private String referredUserId;
        private String referrerUserId;
        private Instant appliedAt=Instant.now();
        public String getId(){return id;} public void setId(String v){id=v;}
        public String getReferredUserId(){return referredUserId;} public void setReferredUserId(String v){referredUserId=v;}
        public String getReferrerUserId(){return referrerUserId;} public void setReferrerUserId(String v){referrerUserId=v;}
        public Instant getAppliedAt(){return appliedAt;} public void setAppliedAt(Instant v){appliedAt=v;}
    }
}
