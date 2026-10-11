package com.pabonaki.controller;

import com.pabonaki.integration.IntegrationService;
import com.pabonaki.model.Order;
import com.pabonaki.model.User;
import com.pabonaki.repo.OrderRepository;
import com.pabonaki.repo.UserRepository;
import com.pabonaki.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class IntegrationController {
    private final IntegrationService integrations; private final UserRepository users; private final OrderRepository orders; private final JwtService jwt; private final PasswordEncoder encoder; private final String frontendOrigin;
    public IntegrationController(IntegrationService integrations, UserRepository users, OrderRepository orders, JwtService jwt, PasswordEncoder encoder, @Value("${app.frontend-origin}") String frontendOrigin){this.integrations=integrations;this.users=users;this.orders=orders;this.jwt=jwt;this.encoder=encoder;this.frontendOrigin=frontendOrigin;}
    public record AiRequest(@NotBlank String message){}
    public record GoogleRequest(@NotBlank String idToken){}
    public record EmailRequest(@NotBlank String to,@NotBlank String subject,@NotBlank String html){}

    @GetMapping("/config/public") public Map<String,Object> publicConfig(){return Map.of("googleClientId",integrations.googleClientId());}
    @GetMapping("/admin/integrations/status") public Map<String,Object> integrationStatus(){return integrations.integrationStatus();}
    @PostMapping("/ai/ask") public Map<String,Object> ask(@Valid @RequestBody AiRequest r){return integrations.askGemini(r.message());}
    @PostMapping("/auth/google") public Map<String,Object> google(@Valid @RequestBody GoogleRequest r){
        Map<String,Object> verified=integrations.verifyGoogleToken(r.idToken());String email=String.valueOf(verified.get("email")).trim().toLowerCase(Locale.ROOT);String name=String.valueOf(verified.getOrDefault("name",email));
        User u=users.findByEmailIgnoreCase(email).orElseGet(()->{User n=new User();n.setEmail(email);n.setName(name);n.setRole("CUSTOMER");n.setPasswordHash(encoder.encode(UUID.randomUUID()+UUID.randomUUID().toString()));return users.save(n);});
        return Map.of("token",jwt.generate(u.getId(),u.getEmail(),u.getRole()),"user",Map.of("id",u.getId(),"name",u.getName(),"email",u.getEmail(),"role",u.getRole()));
    }
    @PostMapping("/admin/media/upload") public ResponseEntity<Map<String,Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(integrations.uploadCloudinary(file));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", safeMessage(ex, "Invalid image file.")));
        } catch (IllegalStateException ex) {
            String message = safeMessage(ex, "Cloudinary is not configured correctly.");
            HttpStatus status = message.contains("not configured") || message.contains("CLOUDINARY_") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
            return ResponseEntity.status(status).body(Map.of("message", message));
        } catch (Exception ex) {
            System.err.println("Cloudinary image upload failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "Cloudinary upload failed. Check Cloudinary credentials and the backend console."));
        }
    }
    private String safeMessage(Exception ex, String fallback) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? fallback : ex.getMessage();
    }
    @PostMapping("/admin/email/send") public Map<String,Object> email(@Valid @RequestBody EmailRequest r){return integrations.sendEmail(r.to(),r.subject(),r.html());}
    @PostMapping("/admin/orders/{id}/courier") public Map<String,Object> courier(@PathVariable("id") String id){Order o=orders.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));if(o.getCourierTrackingId()!=null&&!o.getCourierTrackingId().isBlank())throw new ResponseStatusException(HttpStatus.CONFLICT,"Courier shipment already created: "+o.getCourierTrackingId());try{Map<String,Object> response=integrations.createPathaoShipment(o);Object data=response.get("data");if(data instanceof Map<?,?> details){Object tracking=details.get("consignment_id");if(tracking==null)tracking=details.get("consignmentId");if(tracking==null)tracking=details.get("order_id");if(tracking!=null)o.setCourierTrackingId(String.valueOf(tracking));Object status=details.get("order_status");if(status!=null)o.setCourierStatus(String.valueOf(status));}if(o.getCourierTrackingId()==null)o.setCourierStatus("BOOKING_RESPONSE_RECEIVED");o.setUpdatedAt(Instant.now());orders.save(o);return response;}catch(IllegalStateException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,e.getMessage());}}

    @PostMapping("/payments/{orderId}/sslcommerz/initiate") public Map<String,Object> initiatePayment(Authentication a,@PathVariable("orderId") String orderId){Order o=orders.findByIdAndUserId(orderId,(String)a.getPrincipal()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));if(!"ONLINE".equals(o.getPaymentMethod()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This order is not an online payment order");if("PAID".equalsIgnoreCase(o.getPaymentStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Order already paid");if(!"PENDING".equalsIgnoreCase(o.getStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Only pending orders can start online payment");try{return integrations.initiateSslCommerz(o);}catch(IllegalStateException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,e.getMessage());}}
}
