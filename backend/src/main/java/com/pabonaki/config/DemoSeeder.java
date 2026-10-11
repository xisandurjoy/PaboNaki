package com.pabonaki.config;

import com.pabonaki.model.*;
import com.pabonaki.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;

@Configuration
public class DemoSeeder {
    @Bean
    CommandLineRunner seed(CategoryRepository categories, ProductRepository products,
                           UserRepository users, PasswordEncoder encoder,
                           @Value("${app.seed-demo:false}") boolean enabled) {
        return args -> {
            if (!enabled) return;
            seedAccount(users, encoder, "admin@pabonaki.demo", "PaboAdmin123!", "PaboNaki Admin", "ADMIN");
            seedAccount(users, encoder, "customer@pabonaki.demo", "PaboCustomer123!", "Demo Customer", "CUSTOMER");

            List<String> names = List.of("Fashion & Clothing", "Beauty & Personal Care", "Electronics & Gadgets", "Home & Lifestyle", "Bags, Jewellery & Accessories");
            for (String name : names) {
                if (categories.findByActiveTrueOrderByNameAsc().stream().noneMatch(c -> c.getName().equals(name))) {
                    Category c = new Category(); c.setName(name); c.setSlug(slug(name)); c.setDescription("PaboNaki curated " + name); categories.save(c);
                }
            }
            add(products,"PN-001","Classic Cotton Shirt",names.get(0),990,20,"https://images.unsplash.com/photo-1598033129183-c4f50c736f10?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-002","Daily Glow Skincare Set",names.get(1),1250,15,"https://images.unsplash.com/photo-1608248543803-ba4f8c70ae0b?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-003","Wireless Earbuds",names.get(2),2190,9,"https://images.unsplash.com/photo-1606220945770-b5b6c2c55bf1?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-004","Minimal Desk Lamp",names.get(3),1390,8,"https://images.unsplash.com/photo-1507473885765-e6ed057f782c?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-005","Everyday Tote Bag",names.get(4),850,18,"https://images.unsplash.com/photo-1548036328-c9fa89d128fa?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-006","Soft Knit Cardigan",names.get(0),1650,12,"https://images.unsplash.com/photo-1434389677669-e08b4cac3105?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-007","Ceramic Mug Duo",names.get(3),690,14,"https://images.unsplash.com/photo-1514228742587-6b1558fcca3d?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-008","Everyday Crossbody",names.get(4),1150,11,"https://images.unsplash.com/photo-1584917865442-de89df76afd3?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-009","Wireless Keyboard",names.get(2),1790,10,"https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-010","Hydrating Face Serum",names.get(1),790,16,"https://images.unsplash.com/photo-1601049541289-9b1b7bbbfe19?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-011","Relaxed Fit Denim Jacket",names.get(0),2450,10,"https://images.unsplash.com/photo-1543076447-215ad9ba6923?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-012","Premium Basic T-Shirt",names.get(0),590,24,"https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-013","Classic White Sneakers",names.get(0),1890,13,"https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-014","Everyday Running Shoes",names.get(0),2290,8,"https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-015","Sunglasses Classic",names.get(4),650,20,"https://images.unsplash.com/photo-1511499767150-a48a237f0083?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-016","Minimal Wrist Watch",names.get(4),1590,12,"https://images.unsplash.com/photo-1524592094714-0f0654e20314?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-017","Leather Wallet",names.get(4),790,17,"https://images.unsplash.com/photo-1627123424574-724758594e93?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-018","Travel Backpack",names.get(4),1790,9,"https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-019","Silver Pendant Necklace",names.get(4),550,22,"https://images.unsplash.com/photo-1617038220319-276d3cfab638?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-020","Everyday Hoop Earrings",names.get(4),390,30,"https://images.unsplash.com/photo-1535632066927-ab7c9ab60908?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-021","Moisturizing Body Lotion",names.get(1),520,16,"https://images.unsplash.com/photo-1608248543803-ba4f8c70ae0b?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-022","Gentle Face Cleanser",names.get(1),480,19,"https://images.unsplash.com/photo-1556229010-6c3f2c9ca5f8?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-023","Sunscreen SPF 50",names.get(1),690,15,"https://images.unsplash.com/photo-1556228720-195a672e8a03?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-024","Compact Bluetooth Speaker",names.get(2),1490,11,"https://images.unsplash.com/photo-1608043152269-423dbba4e7e1?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-025","Fast Charging Adapter",names.get(2),790,21,"https://images.unsplash.com/photo-1583863788434-e58a36330cf0?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-026","Smart Fitness Band",names.get(2),1990,7,"https://images.unsplash.com/photo-1557935728-e6d1eaabe558?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-027","Laptop Sleeve 15-inch",names.get(2),850,14,"https://images.unsplash.com/photo-1523206489230-c012c64b2b48?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-028","Ceramic Plant Pot Set",names.get(3),750,12,"https://images.unsplash.com/photo-1485955900006-10f4d324d411?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-029","Soft Cushion Cover Set",names.get(3),590,18,"https://images.unsplash.com/photo-1584100936595-c0654b55a2e2?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-030","Insulated Water Bottle",names.get(3),690,25,"https://images.unsplash.com/photo-1602143407151-7111542de6e8?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-031","Tabletop Storage Organizer",names.get(3),490,16,"https://images.unsplash.com/photo-1593085260707-5377ba37f868?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-032","Portable Power Bank",names.get(2),1290,10,"https://images.unsplash.com/photo-1609091839311-d5365f9ff1c5?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-033","Floral Everyday Scarf",names.get(4),450,18,"https://images.unsplash.com/photo-1601924994987-69e26d50dc26?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-034","Oversized Hoodie",names.get(0),1490,12,"https://images.unsplash.com/photo-1556821840-3a63f95609a7?auto=format&fit=crop&w=800&q=80");
            add(products,"PN-035","Travel Makeup Pouch",names.get(1),390,20,"https://images.unsplash.com/photo-1596462502278-27bfdc403348?auto=format&fit=crop&w=800&q=80");
        };
    }
    private void seedAccount(UserRepository users, PasswordEncoder encoder, String email, String password, String name, String role) {
        User u = users.findByEmailIgnoreCase(email).orElseGet(User::new);
        u.setName(name); u.setEmail(email); u.setPasswordHash(encoder.encode(password)); u.setRole(role); users.save(u);
    }
    private String slug(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""); }
    private void add(ProductRepository r,String sku,String name,String cat,double price,int stock,String img) {
        if (r.existsBySku(sku)) return;
        Product p=new Product(); p.setSku(sku); p.setName(name); p.setSlug(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-")); p.setCategory(cat); p.setDescription("A thoughtful find for your everyday."); p.setPrice(price); p.setStock(stock); p.setImageUrl(img); r.save(p);
    }
}
