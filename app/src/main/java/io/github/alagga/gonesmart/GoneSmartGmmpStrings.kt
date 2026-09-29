package io.github.alagga.gonesmart

import java.util.Locale

/**
 * GoneSmart-only phrases that are NOT present in the installed GMMP APK.
 * Every other injected label must continue to resolve GMMP's own resource.
 *
 * Use the HOST GMMP Activity's current Resources.configuration locale; the
 * companion app intentionally remains English-only. Do not use JVM default
 * Locale here: Android/GMMP may run in a different app-specific language.
 *
 * Language-only entries follow the installed GMMP 4.2.0 resource-table
 * locales. Regional variants inherit their parent except where an actual
 * writing-system difference changes the translation.
 */
internal object GoneSmartGmmpStrings {
    private val moveByLanguage = mapOf(
        "af" to "Skuif",
        "am" to "አንቀሳቅስ",
        "ar" to "نقل",
        "as" to "স্থানান্তৰ কৰক",
        "az" to "Köçür",
        "be" to "Перамясціць",
        "bg" to "Премести",
        "bn" to "সরান",
        "bs" to "Premjesti",
        "ca" to "Mou",
        "cs" to "Přesunout",
        "da" to "Flyt",
        "de" to "Verschieben",
        "el" to "Μετακίνηση",
        "en" to "Move",
        "es" to "Mover",
        "et" to "Teisalda",
        "eu" to "Mugitu",
        "fa" to "انتقال",
        "fi" to "Siirrä",
        "fr" to "Déplacer",
        "gl" to "Mover",
        "gu" to "ખસેડો",
        "he" to "העבר",
        "hi" to "स्थानांतरित करें",
        "hr" to "Premjesti",
        "hu" to "Áthelyezés",
        "hy" to "Տեղափոխել",
        "id" to "Pindahkan",
        "is" to "Færa",
        "it" to "Sposta",
        "ja" to "移動",
        "ka" to "გადატანა",
        "kk" to "Жылжыту",
        "km" to "ផ្លាស់ទី",
        "kn" to "ಸ್ಥಳಾಂತರಿಸಿ",
        "ko" to "이동",
        "ky" to "Жылдыруу",
        "lo" to "ຍ້າຍ",
        "lt" to "Perkelti",
        "lv" to "Pārvietot",
        "mk" to "Премести",
        "ml" to "നീക്കുക",
        "mn" to "Зөөх",
        "mr" to "हलवा",
        "ms" to "Alihkan",
        "my" to "ရွှေ့ရန်",
        "nb" to "Flytt",
        "ne" to "सार्नुहोस्",
        "nl" to "Verplaatsen",
        "or" to "ସ୍ଥାନାନ୍ତର କରନ୍ତୁ",
        "pa" to "ਹਿਲਾਓ",
        "pl" to "Przenieś",
        "pt" to "Mover",
        "ro" to "Mută",
        "ru" to "Переместить",
        "si" to "ගෙනයන්න",
        "sk" to "Presunúť",
        "sl" to "Premakni",
        "sq" to "Zhvendos",
        "sr" to "Премести",
        "sv" to "Flytta",
        "sw" to "Hamisha",
        "ta" to "நகர்த்து",
        "te" to "తరలించు",
        "th" to "ย้าย",
        "tl" to "Ilipat",
        "tr" to "Taşı",
        "uk" to "Перемістити",
        "ur" to "منتقل کریں",
        "uz" to "Ko‘chirish",
        "vi" to "Di chuyển",
        "zh" to "移动",
        "zu" to "Hambisa"
    )

    /**
     * GoneSmart's virtual "Other Locations" node is NOT a physical GMMP
     * folder name. Reuse a genuine installed GMMP translation first if
     * it exists. These are fallback translations for that feature-owned
     * label; native-speaker review is still required before a broad release.
     */
    private val otherLocationsByLanguage = mapOf(
        "af" to "Ander liggings",
        "am" to "ሌሎች ቦታዎች",
        "ar" to "مواقع أخرى",
        "as" to "অন্যান্য স্থান",
        "az" to "Digər yerlər",
        "be" to "Іншыя месцы",
        "bg" to "Други местоположения",
        "bn" to "অন্যান্য অবস্থান",
        "bs" to "Druge lokacije",
        "ca" to "Altres ubicacions",
        "cs" to "Další umístění",
        "da" to "Andre placeringer",
        "de" to "Andere Speicherorte",
        "el" to "Άλλες τοποθεσίες",
        "en" to "Other Locations",
        "es" to "Otras ubicaciones",
        "et" to "Muud asukohad",
        "eu" to "Beste kokapen batzuk",
        "fa" to "مکان‌های دیگر",
        "fi" to "Muut sijainnit",
        "fr" to "Autres emplacements",
        "gl" to "Outras localizacións",
        "gu" to "અન્ય સ્થાનો",
        "he" to "מיקומים אחרים",
        "hi" to "अन्य स्थान",
        "hr" to "Ostale lokacije",
        "hu" to "Egyéb helyek",
        "hy" to "Այլ տեղադրություններ",
        "id" to "Lokasi Lain",
        "is" to "Aðrar staðsetningar",
        "it" to "Altre posizioni",
        "ja" to "その他の場所",
        "ka" to "სხვა მდებარეობები",
        "kk" to "Басқа орындар",
        "km" to "ទីតាំងផ្សេងទៀត",
        "kn" to "ಇತರ ಸ್ಥಳಗಳು",
        "ko" to "다른 위치",
        "ky" to "Башка жерлер",
        "lo" to "ສະຖານທີ່ອື່ນ",
        "lt" to "Kitos vietos",
        "lv" to "Citas atrašanās vietas",
        "mk" to "Други локации",
        "ml" to "മറ്റ് സ്ഥാനങ്ങൾ",
        "mn" to "Бусад байршлууд",
        "mr" to "इतर ठिकाणे",
        "ms" to "Lokasi Lain",
        "my" to "အခြားတည်နေရာများ",
        "nb" to "Andre plasseringer",
        "ne" to "अन्य स्थानहरू",
        "nl" to "Andere locaties",
        "or" to "ଅନ୍ୟ ସ୍ଥାନ",
        "pa" to "ਹੋਰ ਟਿਕਾਣੇ",
        "pl" to "Inne lokalizacje",
        "pt" to "Outros locais",
        "ro" to "Alte locații",
        "ru" to "Другие расположения",
        "si" to "වෙනත් ස්ථාන",
        "sk" to "Iné umiestnenia",
        "sl" to "Druge lokacije",
        "sq" to "Vendndodhje të tjera",
        "sr" to "Друге локације",
        "sv" to "Andra platser",
        "sw" to "Maeneo mengine",
        "ta" to "பிற இடங்கள்",
        "te" to "ఇతర స్థానాలు",
        "th" to "ตำแหน่งอื่น",
        "tl" to "Iba pang lokasyon",
        "tr" to "Diğer konumlar",
        "uk" to "Інші розташування",
        "ur" to "دیگر مقامات",
        "uz" to "Boshqa joylar",
        "vi" to "Vị trí khác",
        "zh" to "其他位置",
        "zu" to "Ezinye izindawo"
    )


    private data class CreationDialogStrings(
        val newFolder: String,
        val folderName: String,
        val newPlaylist: String,
        val playlistName: String,
        val create: String,
        val cancel: String,
        val name: String
    )

    /**
     * Fallback only for creation-dialog phrases that the installed GMMP
     * resources genuinely do not localize. Runtime code always attempts an
     * exact host R.string English->current-locale match first.
     */
    private val creationDialogByLanguage = mapOf(
        "af" to CreationDialogStrings("Nuwe vouer", "Vouernaam", "Nuwe snitlys", "Snitlysnaam", "Skep", "Kanselleer", "Naam"),
        "am" to CreationDialogStrings("አዲስ አቃፊ", "የአቃፊ ስም", "አዲስ የማጫወቻ ዝርዝር", "የማጫወቻ ዝርዝር ስም", "ፍጠር", "ሰርዝ", "ስም"),
        "ar" to CreationDialogStrings("مجلد جديد", "اسم المجلد", "قائمة تشغيل جديدة", "اسم قائمة التشغيل", "إنشاء", "إلغاء", "الاسم"),
        "as" to CreationDialogStrings("নতুন ফ'ল্ডাৰ", "ফ'ল্ডাৰৰ নাম", "নতুন প্লেলিষ্ট", "প্লেলিষ্টৰ নাম", "সৃষ্টি কৰক", "বাতিল কৰক", "নাম"),
        "az" to CreationDialogStrings("Yeni qovluq", "Qovluğun adı", "Yeni pleylist", "Pleylistin adı", "Yarat", "Ləğv et", "Ad"),
        "be" to CreationDialogStrings("Новая папка", "Назва папкі", "Новы плэйліст", "Назва плэйліста", "Стварыць", "Скасаваць", "Назва"),
        "bg" to CreationDialogStrings("Нова папка", "Име на папката", "Нов плейлист", "Име на плейлиста", "Създай", "Отказ", "Име"),
        "bn" to CreationDialogStrings("নতুন ফোল্ডার", "ফোল্ডারের নাম", "নতুন প্লেলিস্ট", "প্লেলিস্টের নাম", "তৈরি করুন", "বাতিল", "নাম"),
        "bs" to CreationDialogStrings("Nova mapa", "Naziv mape", "Nova lista za reprodukciju", "Naziv liste za reprodukciju", "Kreiraj", "Otkaži", "Naziv"),
        "ca" to CreationDialogStrings("Carpeta nova", "Nom de la carpeta", "Llista de reproducció nova", "Nom de la llista de reproducció", "Crea", "Cancel·la", "Nom"),
        "cs" to CreationDialogStrings("Nová složka", "Název složky", "Nový playlist", "Název playlistu", "Vytvořit", "Zrušit", "Název"),
        "da" to CreationDialogStrings("Ny mappe", "Mappenavn", "Ny afspilningsliste", "Navn på afspilningsliste", "Opret", "Annuller", "Navn"),
        "de" to CreationDialogStrings("Neuer Ordner", "Ordnername", "Neue Playlist", "Playlistname", "Erstellen", "Abbrechen", "Name"),
        "el" to CreationDialogStrings("Νέος φάκελος", "Όνομα φακέλου", "Νέα λίστα αναπαραγωγής", "Όνομα λίστας αναπαραγωγής", "Δημιουργία", "Ακύρωση", "Όνομα"),
        "en" to CreationDialogStrings("New folder", "Folder name", "New playlist", "Playlist name", "Create", "Cancel", "Name"),
        "es" to CreationDialogStrings("Nueva carpeta", "Nombre de la carpeta", "Nueva lista de reproducción", "Nombre de la lista de reproducción", "Crear", "Cancelar", "Nombre"),
        "et" to CreationDialogStrings("Uus kaust", "Kausta nimi", "Uus esitusloend", "Esitusloendi nimi", "Loo", "Tühista", "Nimi"),
        "eu" to CreationDialogStrings("Karpeta berria", "Karpetaren izena", "Erreprodukzio-zerrenda berria", "Erreprodukzio-zerrendaren izena", "Sortu", "Utzi", "Izena"),
        "fa" to CreationDialogStrings("پوشه جدید", "نام پوشه", "فهرست پخش جدید", "نام فهرست پخش", "ایجاد", "لغو", "نام"),
        "fi" to CreationDialogStrings("Uusi kansio", "Kansion nimi", "Uusi soittolista", "Soittolistan nimi", "Luo", "Peruuta", "Nimi"),
        "fr" to CreationDialogStrings("Nouveau dossier", "Nom du dossier", "Nouvelle playlist", "Nom de la playlist", "Créer", "Annuler", "Nom"),
        "gl" to CreationDialogStrings("Novo cartafol", "Nome do cartafol", "Nova lista de reprodución", "Nome da lista de reprodución", "Crear", "Cancelar", "Nome"),
        "gu" to CreationDialogStrings("નવું ફોલ્ડર", "ફોલ્ડરનું નામ", "નવી પ્લેલિસ્ટ", "પ્લેલિસ્ટનું નામ", "બનાવો", "રદ કરો", "નામ"),
        "he" to CreationDialogStrings("תיקייה חדשה", "שם התיקייה", "רשימת השמעה חדשה", "שם רשימת ההשמעה", "יצירה", "ביטול", "שם"),
        "hi" to CreationDialogStrings("नया फ़ोल्डर", "फ़ोल्डर का नाम", "नई प्लेलिस्ट", "प्लेलिस्ट का नाम", "बनाएँ", "रद्द करें", "नाम"),
        "hr" to CreationDialogStrings("Nova mapa", "Naziv mape", "Novi popis za reprodukciju", "Naziv popisa za reprodukciju", "Stvori", "Odustani", "Naziv"),
        "hu" to CreationDialogStrings("Új mappa", "Mappa neve", "Új lejátszási lista", "Lejátszási lista neve", "Létrehozás", "Mégse", "Név"),
        "hy" to CreationDialogStrings("Նոր պանակ", "Պանակի անունը", "Նոր երգացանկ", "Երգացանկի անունը", "Ստեղծել", "Չեղարկել", "Անուն"),
        "id" to CreationDialogStrings("Folder baru", "Nama folder", "Playlist baru", "Nama playlist", "Buat", "Batal", "Nama"),
        "is" to CreationDialogStrings("Ný mappa", "Heiti möppu", "Nýr spilunarlisti", "Heiti spilunarlista", "Búa til", "Hætta við", "Heiti"),
        "it" to CreationDialogStrings("Nuova cartella", "Nome cartella", "Nuova playlist", "Nome playlist", "Crea", "Annulla", "Nome"),
        "ja" to CreationDialogStrings("新しいフォルダー", "フォルダー名", "新しいプレイリスト", "プレイリスト名", "作成", "キャンセル", "名前"),
        "ka" to CreationDialogStrings("ახალი საქაღალდე", "საქაღალდის სახელი", "ახალი დასაკრავი სია", "დასაკრავი სიის სახელი", "შექმნა", "გაუქმება", "სახელი"),
        "kk" to CreationDialogStrings("Жаңа қалта", "Қалта атауы", "Жаңа ойнату тізімі", "Ойнату тізімінің атауы", "Жасау", "Болдырмау", "Атауы"),
        "km" to CreationDialogStrings("ថតថ្មី", "ឈ្មោះថត", "បញ្ជីចាក់ថ្មី", "ឈ្មោះបញ្ជីចាក់", "បង្កើត", "បោះបង់", "ឈ្មោះ"),
        "kn" to CreationDialogStrings("ಹೊಸ ಫೋಲ್ಡರ್", "ಫೋಲ್ಡರ್ ಹೆಸರು", "ಹೊಸ ಪ್ಲೇಪಟ್ಟಿ", "ಪ್ಲೇಪಟ್ಟಿ ಹೆಸರು", "ರಚಿಸಿ", "ರದ್ದುಮಾಡಿ", "ಹೆಸರು"),
        "ko" to CreationDialogStrings("새 폴더", "폴더 이름", "새 재생목록", "재생목록 이름", "만들기", "취소", "이름"),
        "ky" to CreationDialogStrings("Жаңы папка", "Папканын аты", "Жаңы ойнотмо тизме", "Ойнотмо тизменин аты", "Түзүү", "Жокко чыгаруу", "Аты"),
        "lo" to CreationDialogStrings("ໂຟນເດີໃໝ່", "ຊື່ໂຟນເດີ", "ລາຍການຫຼິ້ນໃໝ່", "ຊື່ລາຍການຫຼິ້ນ", "ສ້າງ", "ຍົກເລີກ", "ຊື່"),
        "lt" to CreationDialogStrings("Naujas aplankas", "Aplanko pavadinimas", "Naujas grojaraštis", "Grojaraščio pavadinimas", "Kurti", "Atšaukti", "Pavadinimas"),
        "lv" to CreationDialogStrings("Jauna mape", "Mapes nosaukums", "Jauns atskaņošanas saraksts", "Atskaņošanas saraksta nosaukums", "Izveidot", "Atcelt", "Nosaukums"),
        "mk" to CreationDialogStrings("Нова папка", "Име на папката", "Нова плејлиста", "Име на плејлистата", "Создај", "Откажи", "Име"),
        "ml" to CreationDialogStrings("പുതിയ ഫോൾഡർ", "ഫോൾഡറിന്റെ പേര്", "പുതിയ പ്ലേലിസ്റ്റ്", "പ്ലേലിസ്റ്റിന്റെ പേര്", "സൃഷ്ടിക്കുക", "റദ്ദാക്കുക", "പേര്"),
        "mn" to CreationDialogStrings("Шинэ хавтас", "Хавтасны нэр", "Шинэ тоглуулах жагсаалт", "Тоглуулах жагсаалтын нэр", "Үүсгэх", "Цуцлах", "Нэр"),
        "mr" to CreationDialogStrings("नवीन फोल्डर", "फोल्डरचे नाव", "नवीन प्लेलिस्ट", "प्लेलिस्टचे नाव", "तयार करा", "रद्द करा", "नाव"),
        "ms" to CreationDialogStrings("Folder baharu", "Nama folder", "Senarai main baharu", "Nama senarai main", "Cipta", "Batal", "Nama"),
        "my" to CreationDialogStrings("ဖိုလ်ဒါအသစ်", "ဖိုလ်ဒါအမည်", "ဖွင့်စာရင်းအသစ်", "ဖွင့်စာရင်းအမည်", "ဖန်တီးရန်", "ပယ်ဖျက်", "အမည်"),
        "nb" to CreationDialogStrings("Ny mappe", "Mappenavn", "Ny spilleliste", "Navn på spilleliste", "Opprett", "Avbryt", "Navn"),
        "ne" to CreationDialogStrings("नयाँ फोल्डर", "फोल्डरको नाम", "नयाँ प्लेलिस्ट", "प्लेलिस्टको नाम", "सिर्जना गर्नुहोस्", "रद्द गर्नुहोस्", "नाम"),
        "nl" to CreationDialogStrings("Nieuwe map", "Mapnaam", "Nieuwe afspeellijst", "Naam van afspeellijst", "Maken", "Annuleren", "Naam"),
        "or" to CreationDialogStrings("ନୂଆ ଫୋଲ୍ଡର", "ଫୋଲ୍ଡର ନାମ", "ନୂଆ ପ୍ଲେଲିଷ୍ଟ", "ପ୍ଲେଲିଷ୍ଟ ନାମ", "ସୃଷ୍ଟି କରନ୍ତୁ", "ବାତିଲ୍", "ନାମ"),
        "pa" to CreationDialogStrings("ਨਵਾਂ ਫੋਲਡਰ", "ਫੋਲਡਰ ਦਾ ਨਾਮ", "ਨਵੀਂ ਪਲੇਲਿਸਟ", "ਪਲੇਲਿਸਟ ਦਾ ਨਾਮ", "ਬਣਾਓ", "ਰੱਦ ਕਰੋ", "ਨਾਮ"),
        "pl" to CreationDialogStrings("Nowy folder", "Nazwa folderu", "Nowa playlista", "Nazwa playlisty", "Utwórz", "Anuluj", "Nazwa"),
        "pt" to CreationDialogStrings("Nova pasta", "Nome da pasta", "Nova lista de reprodução", "Nome da lista de reprodução", "Criar", "Cancelar", "Nome"),
        "ro" to CreationDialogStrings("Dosar nou", "Numele dosarului", "Listă de redare nouă", "Numele listei de redare", "Creează", "Anulează", "Nume"),
        "ru" to CreationDialogStrings("Новая папка", "Имя папки", "Новый плейлист", "Имя плейлиста", "Создать", "Отмена", "Имя"),
        "si" to CreationDialogStrings("නව ෆෝල්ඩරය", "ෆෝල්ඩරයේ නම", "නව ධාවන ලැයිස්තුව", "ධාවන ලැයිස්තුවේ නම", "සාදන්න", "අවලංගු කරන්න", "නම"),
        "sk" to CreationDialogStrings("Nový priečinok", "Názov priečinka", "Nový zoznam skladieb", "Názov zoznamu skladieb", "Vytvoriť", "Zrušiť", "Názov"),
        "sl" to CreationDialogStrings("Nova mapa", "Ime mape", "Nov seznam predvajanja", "Ime seznama predvajanja", "Ustvari", "Prekliči", "Ime"),
        "sq" to CreationDialogStrings("Dosje e re", "Emri i dosjes", "Listë e re luajtjeje", "Emri i listës së luajtjes", "Krijo", "Anulo", "Emri"),
        "sr" to CreationDialogStrings("Нова фасцикла", "Назив фасцикле", "Нова листа за репродукцију", "Назив листе за репродукцију", "Направи", "Откажи", "Назив"),
        "sv" to CreationDialogStrings("Ny mapp", "Mappnamn", "Ny spellista", "Namn på spellista", "Skapa", "Avbryt", "Namn"),
        "sw" to CreationDialogStrings("Folda mpya", "Jina la folda", "Orodha mpya ya kucheza", "Jina la orodha ya kucheza", "Unda", "Ghairi", "Jina"),
        "ta" to CreationDialogStrings("புதிய கோப்புறை", "கோப்புறை பெயர்", "புதிய பிளேலிஸ்ட்", "பிளேலிஸ்ட் பெயர்", "உருவாக்கு", "ரத்துசெய்", "பெயர்"),
        "te" to CreationDialogStrings("కొత్త ఫోల్డర్", "ఫోల్డర్ పేరు", "కొత్త ప్లేలిస్ట్", "ప్లేలిస్ట్ పేరు", "సృష్టించు", "రద్దు చేయి", "పేరు"),
        "th" to CreationDialogStrings("โฟลเดอร์ใหม่", "ชื่อโฟลเดอร์", "เพลย์ลิสต์ใหม่", "ชื่อเพลย์ลิสต์", "สร้าง", "ยกเลิก", "ชื่อ"),
        "tl" to CreationDialogStrings("Bagong folder", "Pangalan ng folder", "Bagong playlist", "Pangalan ng playlist", "Gumawa", "Kanselahin", "Pangalan"),
        "tr" to CreationDialogStrings("Yeni klasör", "Klasör adı", "Yeni çalma listesi", "Çalma listesi adı", "Oluştur", "İptal", "Ad"),
        "uk" to CreationDialogStrings("Нова папка", "Назва папки", "Новий плейлист", "Назва плейлиста", "Створити", "Скасувати", "Назва"),
        "ur" to CreationDialogStrings("نیا فولڈر", "فولڈر کا نام", "نئی پلے لسٹ", "پلے لسٹ کا نام", "بنائیں", "منسوخ کریں", "نام"),
        "uz" to CreationDialogStrings("Yangi jild", "Jild nomi", "Yangi pleylist", "Pleylist nomi", "Yaratish", "Bekor qilish", "Nomi"),
        "vi" to CreationDialogStrings("Thư mục mới", "Tên thư mục", "Danh sách phát mới", "Tên danh sách phát", "Tạo", "Hủy", "Tên"),
        "zh" to CreationDialogStrings("新建文件夹", "文件夹名称", "新建播放列表", "播放列表名称", "创建", "取消", "名称"),
        "zu" to CreationDialogStrings("Ifolda entsha", "Igama lefolda", "Uhlu lokudlalayo olusha", "Igama lohlu lokudlalayo", "Dala", "Khansela", "Igama")
    )

    internal val translatedCreationDialogLanguageCodes: Set<String>
        get() = creationDialogByLanguage.keys

    fun creationDialog(locale: Locale, sourceEnglish: String): String? {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        val base = if (language == "zh" && (
                locale.country.equals("TW", ignoreCase = true) ||
                locale.country.equals("HK", ignoreCase = true) ||
                locale.script.equals("Hant", ignoreCase = true)
            )
        ) {
            CreationDialogStrings(
                "新增資料夾",
                "資料夾名稱",
                "新增播放清單",
                "播放清單名稱",
                "建立",
                "取消",
                "名稱"
            )
        } else {
            creationDialogByLanguage[language]
                ?: creationDialogByLanguage.getValue("en")
        }
        val key = sourceEnglish.trim()
            .replace('…', '.')
            .trimEnd('.', ':')
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
        return when (key) {
            "new folder", "create new folder", "create a new folder",
            "create folder" ->
                base.newFolder
            "folder name", "new folder name",
            "enter folder name", "enter a folder name" ->
                base.folderName
            "new playlist", "create new playlist", "create a new playlist",
            "create playlist" ->
                base.newPlaylist
            "playlist name", "new playlist name",
            "enter playlist name", "enter a playlist name" ->
                base.playlistName
            "create" -> base.create
            "cancel" -> base.cancel
            "name" -> base.name
            else -> null
        }
    }

    fun hasCreationDialogTranslation(locale: Locale): Boolean {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        return creationDialogByLanguage.containsKey(language)
    }

    internal val translatedOtherLocationsLanguageCodes: Set<String>
        get() = otherLocationsByLanguage.keys

    fun otherLocations(locale: Locale): String {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        return otherLocationsByLanguage[language]
            ?: otherLocationsByLanguage.getValue("en")
    }

    fun hasOtherLocationsTranslation(locale: Locale): Boolean {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        return otherLocationsByLanguage.containsKey(language)
    }

    /** Explicit audit contract for the installed GMMP 4.2.0 locales. */
    internal val translatedLanguageCodes: Set<String>
        get() = moveByLanguage.keys

    /** Actual GMMP 4.2.0 resources also include zh-HK and zh-TW. */
    fun move(locale: Locale): String {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        if (language == "zh" && (
                locale.country.equals("TW", ignoreCase = true) ||
                locale.country.equals("HK", ignoreCase = true) ||
                locale.script.equals("Hant", ignoreCase = true)
            )
        ) return "移動"
        return moveByLanguage[language] ?: moveByLanguage.getValue("en")
    }

    fun hasMoveTranslation(locale: Locale): Boolean {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "no" -> "nb"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        return moveByLanguage.containsKey(language)
    }
}
