--liquibase formatted sql

--changeset BLADBORNE:010_insert_products
INSERT INTO market.product (title, description, img_path, price)
VALUES ('Ноутбук ASUS VivoBook', '15.6" Full HD, Intel Core i5, 8GB RAM, 512GB SSD', 'images/product1.jpg', 54999),
       ('Смартфон Samsung Galaxy', '6.5" AMOLED, 128GB, 5000mAh, камера 50MP', 'images/product2.jpg', 34999),
       ('Наушники Sony WH-1000XM5', 'Беспроводные, шумоподавление, 30ч автономность', 'images/product3.jpg', 29999),
       ('Умные часы Apple Watch', '45mm, GPS, водозащита, мониторинг здоровья', 'images/product4.jpg', 44999),
       ('Планшет iPad Air', '10.9" Liquid Retina, M1, 64GB, Wi-Fi', 'images/product5.jpg', 59999),
       ('Игровая мышь Logitech G502', 'HERO 25K сенсор, 11 программируемых кнопок', 'images/product6.jpg', 7999),
       ('Клавиатура Mechanical Keychron', 'Беспроводная, RGB подсветка, горячая замена', 'images/product7.jpg', 12999),
       ('Монитор LG UltraWide', '29" IPS, 2560x1080, 75Hz, USB-C', 'images/product8.jpg', 22999),
       ('Веб-камера Logitech C920', 'Full HD 1080p, автофокус, стерео микрофон', 'images/product9.jpg', 6999),
       ('Внешний SSD Samsung T7', '1TB, USB 3.2, скорость до 1050MB/s', 'images/product10.jpg', 9999),
       ('Роутер TP-Link Archer', 'Wi-Fi 6, 1800Mbps, MU-MIMO, родительский контроль', 'images/product11.jpg', 4999),
       ('Портативная колонка JBL Flip', 'Bluetooth, 20ч, водозащита IPX7, JBL PartyBoost', 'images/product12.jpg',
        8999),
       ('Фитнес-браслет Xiaomi Band', 'AMOLED 1.62", пульсоксиметр, 15 дней автономность', 'images/product13.jpg',
        3999),
       ('Электрическая зубная щетка Oral-B', 'ИИ-технология, 6 режимов, датчик давления', 'images/product14.jpg', 7999),
       ('Кофеварка DeLonghi', 'Эспрессо, 15 бар, капучинатор, 1.8л', 'images/product15.jpg', 18999),
       ('Робот-пылесос Roborock', 'Лазерная навигация, 5500Pa, влажная уборка', 'images/product16.jpg', 32999),
       ('Умная лампа Philips Hue', 'LED, 16 млн цветов, Wi-Fi, совместимость с Alexa', 'images/product17.jpg', 2999),
       ('Электросамокат Xiaomi Mi', '25км/ч, 45км автономность, диски 10"', 'images/product18.jpg', 24999),
       ('Игровая консоль PlayStation 5', '825GB SSD, 4K, HDR, обратная совместимость', 'images/product19.jpg', 54999),
       ('VR-шлем Meta Quest 3', '128GB, самостоятельный, 4K+, отслеживание рук', 'images/product20.jpg', 44999);
