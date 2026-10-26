USE chehreApp;
INSERT INTO app_categories (name, slug, sort_order, is_active) VALUES
('تاریخی','tarikhi',1,1),
('فانتزی','fantazi',2,1),
('آینده‌نگر','ayandeh',3,1),
('هنری','honari',4,1);
INSERT INTO app_worlds (name, slug, category_id, sample_image_url, hidden_prompt, model_id, coin_price, is_featured, is_active, sort_order) VALUES
('دنیای قاجار','qajar',(SELECT id FROM app_categories WHERE slug='tarikhi'),'http://171.22.25.152:5001/static/worlds/qajar.jpg','Transform this portrait into a regal Qajar-era Persian royal portrait painting. Keep the face, features and likeness exactly recognizable. Ornate embroidered robes, tall Qajar crown, classical Persian court painting style, rich warm colors, palace hall background.',1,5,1,1,1),
('دنیای انیمه','anime',(SELECT id FROM app_categories WHERE slug='fantazi'),'http://171.22.25.152:5001/static/worlds/anime.jpg','Transform this portrait into an anime style portrait. Keep the face and likeness recognizable. Large expressive eyes, clean cel shading, vibrant colors, Japanese animation style.',1,5,1,1,2),
('دنیای سایبرپانک','cyberpunk',(SELECT id FROM app_categories WHERE slug='ayandeh'),'http://171.22.25.152:5001/static/worlds/cyberpunk.jpg','Transform this portrait into a cyberpunk portrait. Keep the face recognizable. Glowing neon implants, futuristic jacket, neon-lit rainy megacity background, pink and cyan cinematic lighting.',1,5,0,1,3),
('نقاشی رنگ‌روغن','oil-painting',(SELECT id FROM app_categories WHERE slug='honari'),'http://171.22.25.152:5001/static/worlds/oil.jpg','Transform this portrait into a classical European oil painting. Keep the face and likeness recognizable. Elegant period clothing, soft Rembrandt lighting, dark background, museum masterpiece style.',1,5,0,1,4);
INSERT INTO app_coin_packs (name, coins, price_toman, bazaar_sku, badge, sort_order, is_active) VALUES
('شروع',15,29000,'pack_starter',NULL,1,1),
('رشد',40,69000,'pack_growth',NULL,2,1),
('حرفه‌ای',100,159000,'pack_pro','محبوب‌ترین',3,1),
('ویژه',200,299000,'pack_special',NULL,4,1);
