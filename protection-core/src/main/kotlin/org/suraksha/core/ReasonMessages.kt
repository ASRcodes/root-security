package org.suraksha.core

object ReasonMessages {
    private val messages = mapOf(
        ReasonCode.GOOGLE_REPUTATION to listOf("Google Safe Browsing reported a possible threat for this link at scan time. Advisory provided by Google. The finding may be incorrect.", "जाँच के समय Google Safe Browsing ने इस लिंक के लिए संभावित ख़तरा बताया। Advisory provided by Google. यह नतीजा गलत भी हो सकता है।", "ਜਾਂਚ ਵੇਲੇ Google Safe Browsing ਨੇ ਇਸ ਲਿੰਕ ਲਈ ਸੰਭਾਵੀ ਖ਼ਤਰਾ ਦੱਸਿਆ। Advisory provided by Google. ਇਹ ਨਤੀਜਾ ਗਲਤ ਵੀ ਹੋ ਸਕਦਾ ਹੈ।"),
        ReasonCode.REPUTATION_NO_MATCH to listOf("The online check found no known threat at scan time. This is not a guarantee of safety.", "ऑनलाइन जाँच के समय कोई ज्ञात ख़तरा नहीं मिला। यह सुरक्षित होने की गारंटी नहीं है।", "ਆਨਲਾਈਨ ਜਾਂਚ ਵੇਲੇ ਕੋਈ ਜਾਣਿਆ ਖ਼ਤਰਾ ਨਹੀਂ ਮਿਲਿਆ। ਇਹ ਸੁਰੱਖਿਅਤ ਹੋਣ ਦੀ ਗਾਰੰਟੀ ਨਹੀਂ ਹੈ।"),
        ReasonCode.REPUTATION_NOT_CHECKED to listOf("A current online reputation check was not completed. This does not mean the link is safe.", "मौजूदा ऑनलाइन प्रतिष्ठा जाँच पूरी नहीं हुई। इसका मतलब यह नहीं कि लिंक सुरक्षित है।", "ਮੌਜੂਦਾ ਆਨਲਾਈਨ ਸਾਖ ਦੀ ਜਾਂਚ ਪੂਰੀ ਨਹੀਂ ਹੋਈ। ਇਸ ਦਾ ਮਤਲਬ ਇਹ ਨਹੀਂ ਕਿ ਲਿੰਕ ਸੁਰੱਖਿਅਤ ਹੈ।"),
        ReasonCode.PROVIDER_TEST to listOf("This is an official Google Safe Browsing TEST URL. It is a harmless test of the real lookup. No guardian SMS is sent.", "यह Google Safe Browsing का आधिकारिक परीक्षण URL है। यह असली ऑनलाइन जाँच का हानिरहित परीक्षण है। परिवार संपर्क को SMS नहीं भेजा जाता।", "ਇਹ Google Safe Browsing ਦਾ ਅਧਿਕਾਰਤ ਟੈਸਟ URL ਹੈ। ਇਹ ਅਸਲੀ ਆਨਲਾਈਨ ਜਾਂਚ ਦਾ ਨੁਕਸਾਨ ਰਹਿਤ ਟੈਸਟ ਹੈ। ਪਰਿਵਾਰਕ ਸੰਪਰਕ ਨੂੰ SMS ਨਹੀਂ ਭੇਜਿਆ ਜਾਂਦਾ।"),
        ReasonCode.FILE_SCAN to listOf("This file was checked locally.", "इस फ़ाइल की जाँच फ़ोन पर हुई।", "ਇਸ ਫ਼ਾਈਲ ਦੀ ਜਾਂਚ ਫ਼ੋਨ ਉੱਤੇ ਹੋਈ।"),
        ReasonCode.EICAR_TEST to listOf("This is the harmless EICAR test file. It is not a real virus.", "यह हानिरहित EICAR परीक्षण फ़ाइल है। यह असली वायरस नहीं है।", "ਇਹ ਨੁਕਸਾਨ ਰਹਿਤ EICAR ਟੈਸਟ ਫ਼ਾਈਲ ਹੈ। ਇਹ ਅਸਲੀ ਵਾਇਰਸ ਨਹੀਂ ਹੈ।"),
        ReasonCode.DEMO_ONLY to listOf("Simulation only. This does not assess a link or send guardian SMS.", "केवल चेतावनी का प्रदर्शन। यह लिंक की जाँच नहीं करता और परिवार संपर्क को SMS नहीं भेजता।", "ਸਿਰਫ਼ ਚੇਤਾਵਨੀ ਦਾ ਪ੍ਰਦਰਸ਼ਨ। ਇਹ ਲਿੰਕ ਦੀ ਜਾਂਚ ਨਹੀਂ ਕਰਦਾ ਅਤੇ ਪਰਿਵਾਰਕ ਸੰਪਰਕ ਨੂੰ SMS ਨਹੀਂ ਭੇਜਦਾ।"),
        ReasonCode.TEST_INPUT to listOf("Harmless reserved .example test address. The normal local rules assessed this input; it is not a verified real threat.", "यह हानिरहित आरक्षित .example परीक्षण पता है। सामान्य स्थानीय नियमों ने इसकी जाँच की है; यह किसी वास्तविक ख़तरे की पुष्टि नहीं है।", "ਇਹ ਨੁਕਸਾਨ ਰਹਿਤ ਰਾਖਵਾਂ .example ਟੈਸਟ ਪਤਾ ਹੈ। ਆਮ ਸਥਾਨਕ ਨਿਯਮਾਂ ਨੇ ਇਸ ਦੀ ਜਾਂਚ ਕੀਤੀ ਹੈ; ਇਹ ਕਿਸੇ ਅਸਲੀ ਖ਼ਤਰੇ ਦੀ ਪੁਸ਼ਟੀ ਨਹੀਂ ਹੈ।"),
        ReasonCode.TRUSTED_DOMAIN to listOf("This is an exact match for a trusted domain.", "यह भरोसेमंद डोमेन से ठीक मेल खाता है।", "ਇਹ ਭਰੋਸੇਯੋਗ ਡੋਮੇਨ ਨਾਲ ਪੂਰੀ ਤਰ੍ਹਾਂ ਮੇਲ ਖਾਂਦਾ ਹੈ।"),
        ReasonCode.KNOWN_PHISHING to listOf("This domain is in the offline phishing list.", "यह डोमेन ऑफ़लाइन धोखाधड़ी सूची में है।", "ਇਹ ਡੋਮੇਨ ਆਫ਼ਲਾਈਨ ਧੋਖਾਧੜੀ ਸੂਚੀ ਵਿੱਚ ਹੈ।"),
        ReasonCode.PUNYCODE to listOf("The address uses encoded international characters. Check it carefully.", "पते में बदले हुए अंतरराष्ट्रीय अक्षर हैं। ध्यान से जाँचें।", "ਪਤੇ ਵਿੱਚ ਬਦਲੇ ਹੋਏ ਅੰਤਰਰਾਸ਼ਟਰੀ ਅੱਖਰ ਹਨ। ਧਿਆਨ ਨਾਲ ਜਾਂਚੋ।"),
        ReasonCode.LOOKALIKE to listOf("The address looks like a known brand with changed letters.", "पता किसी मशहूर नाम जैसा है, लेकिन अक्षर बदले हुए हैं।", "ਪਤਾ ਕਿਸੇ ਜਾਣੇ-ਪਛਾਣੇ ਨਾਮ ਵਰਗਾ ਹੈ, ਪਰ ਅੱਖਰ ਬਦਲੇ ਹੋਏ ਹਨ।"),
        ReasonCode.BRAND_IN_SUBDOMAIN to listOf("A familiar brand name hides a different website owner.", "जान-पहचान के नाम के पीछे किसी और की वेबसाइट है।", "ਜਾਣੇ-ਪਛਾਣੇ ਨਾਮ ਦੇ ਪਿੱਛੇ ਕਿਸੇ ਹੋਰ ਦੀ ਵੈੱਬਸਾਈਟ ਹੈ।"),
        ReasonCode.IP_HOST to listOf("This link uses a number instead of a website name.", "इस लिंक में वेबसाइट के नाम की जगह नंबर है।", "ਇਸ ਲਿੰਕ ਵਿੱਚ ਵੈੱਬਸਾਈਟ ਦੇ ਨਾਮ ਦੀ ਥਾਂ ਨੰਬਰ ਹੈ।"),
        ReasonCode.RISKY_TLD to listOf("The address ending is often used in suspicious links.", "पते का यह आख़िरी हिस्सा संदिग्ध लिंक में अक्सर दिखता है।", "ਪਤੇ ਦਾ ਇਹ ਆਖ਼ਰੀ ਹਿੱਸਾ ਸ਼ੱਕੀ ਲਿੰਕਾਂ ਵਿੱਚ ਅਕਸਰ ਦਿਖਦਾ ਹੈ।"),
        ReasonCode.MANY_HYPHENS to listOf("The website name has an unusual number of dashes.", "वेबसाइट के नाम में असामान्य रूप से कई डैश हैं।", "ਵੈੱਬਸਾਈਟ ਦੇ ਨਾਮ ਵਿੱਚ ਅਸਾਧਾਰਨ ਤੌਰ ਉੱਤੇ ਕਈ ਡੈਸ਼ ਹਨ।"),
        ReasonCode.LONG_URL to listOf("This address is unusually long.", "यह पता असामान्य रूप से लंबा है।", "ਇਹ ਪਤਾ ਅਸਾਧਾਰਨ ਤੌਰ ਉੱਤੇ ਲੰਮਾ ਹੈ।"),
        ReasonCode.SHORTENER to listOf("This short link may lead to another website. Redirects and the final website were not inspected.", "यह छोटा लिंक दूसरी वेबसाइट पर ले जा सकता है। आगे के लिंक और अंतिम वेबसाइट की जाँच नहीं हुई।", "ਇਹ ਛੋਟਾ ਲਿੰਕ ਦੂਜੀ ਵੈੱਬਸਾਈਟ ਉੱਤੇ ਲੈ ਜਾ ਸਕਦਾ ਹੈ। ਅਗਲੇ ਲਿੰਕਾਂ ਅਤੇ ਅੰਤਿਮ ਵੈੱਬਸਾਈਟ ਦੀ ਜਾਂਚ ਨਹੀਂ ਹੋਈ।"),
        ReasonCode.CREDENTIAL_PATH to listOf("The link refers to sign-in, verification or payment details.", "लिंक में लॉगिन, पहचान की जाँच या भुगतान की बात है।", "ਲਿੰਕ ਵਿੱਚ ਲੌਗਇਨ, ਪਛਾਣ ਦੀ ਜਾਂਚ ਜਾਂ ਭੁਗਤਾਨ ਦੀ ਗੱਲ ਹੈ।"),
        ReasonCode.USER_INFO to listOf("The address contains an @ sign that can disguise the real website.", "पते में @ चिह्न है जो असली वेबसाइट छिपा सकता है।", "ਪਤੇ ਵਿੱਚ @ ਚਿੰਨ੍ਹ ਹੈ ਜੋ ਅਸਲੀ ਵੈੱਬਸਾਈਟ ਲੁਕਾ ਸਕਦਾ ਹੈ।"),
        ReasonCode.URL_MODEL to listOf("A local model found a suspicious pattern. It does not confirm a harmful link.", "फ़ोन के मॉडल को संदिग्ध पैटर्न मिला। इससे हानिकारक लिंक की पुष्टि नहीं होती।", "ਫ਼ੋਨ ਦੇ ਮਾਡਲ ਨੂੰ ਸ਼ੱਕੀ ਪੈਟਰਨ ਮਿਲਿਆ। ਇਸ ਨਾਲ ਨੁਕਸਾਨਦੇਹ ਲਿੰਕ ਦੀ ਪੁਸ਼ਟੀ ਨਹੀਂ ਹੁੰਦੀ।"),
        ReasonCode.SCAM_LANGUAGE to listOf("The message uses language associated with scams or pressure.", "संदेश में धोखाधड़ी या दबाव डालने जैसी भाषा है।", "ਸੁਨੇਹੇ ਵਿੱਚ ਧੋਖਾਧੜੀ ਜਾਂ ਦਬਾਅ ਪਾਉਣ ਵਰਗੀ ਭਾਸ਼ਾ ਹੈ।"),
        ReasonCode.INVALID_URL to listOf("The website address could not be read safely.", "वेबसाइट का पता सुरक्षित तरीके से पढ़ा नहीं जा सका।", "ਵੈੱਬਸਾਈਟ ਦਾ ਪਤਾ ਸੁਰੱਖਿਅਤ ਢੰਗ ਨਾਲ ਪੜ੍ਹਿਆ ਨਹੀਂ ਜਾ ਸਕਿਆ।"),
        ReasonCode.KNOWN_BAD_HASH to listOf("This file matches a known harmful-file signature.", "यह फ़ाइल ज्ञात नुकसानदेह फ़ाइल से मेल खाती है।", "ਇਹ ਫ਼ਾਈਲ ਜਾਣੀ-ਪਛਾਣੀ ਨੁਕਸਾਨਦੇਹ ਫ਼ਾਈਲ ਨਾਲ ਮੇਲ ਖਾਂਦੀ ਹੈ।"),
        ReasonCode.RISKY_PERMISSIONS to listOf("This app asks for powerful phone access. Only install it if you trust its source.", "यह ऐप फ़ोन पर शक्तिशाली अनुमतियाँ माँगता है। भरोसेमंद जगह से ही इसे इंस्टॉल करें।", "ਇਹ ਐਪ ਫ਼ੋਨ ਉੱਤੇ ਸ਼ਕਤੀਸ਼ਾਲੀ ਇਜਾਜ਼ਤਾਂ ਮੰਗਦਾ ਹੈ। ਭਰੋਸੇਯੋਗ ਥਾਂ ਤੋਂ ਹੀ ਇਸ ਨੂੰ ਇੰਸਟਾਲ ਕਰੋ।"),
        ReasonCode.SUSPICIOUS_FILENAME to listOf("This APK name resembles a common scam lure.", "इस APK का नाम आम धोखाधड़ी के बहाने जैसा है।", "ਇਸ APK ਦਾ ਨਾਮ ਆਮ ਧੋਖਾਧੜੀ ਦੇ ਬਹਾਨੇ ਵਰਗਾ ਹੈ।"),
        ReasonCode.UNREADABLE_FILE to listOf("This file could not be checked. Treat it with caution.", "इस फ़ाइल की जाँच नहीं हो सकी। सावधानी बरतें।", "ਇਸ ਫ਼ਾਈਲ ਦੀ ਜਾਂਚ ਨਹੀਂ ਹੋ ਸਕੀ। ਸਾਵਧਾਨ ਰਹੋ।"),
        ReasonCode.NO_KNOWN_THREAT to listOf("No known warning signs were found. This is not a guarantee.", "कोई ज्ञात ख़तरे का संकेत नहीं मिला। यह गारंटी नहीं है।", "ਕੋਈ ਜਾਣਿਆ ਖ਼ਤਰੇ ਦਾ ਸੰਕੇਤ ਨਹੀਂ ਮਿਲਿਆ। ਇਹ ਗਾਰੰਟੀ ਨਹੀਂ ਹੈ।"),
        ReasonCode.LOCAL_RISK to listOf("Several local warning signs suggest elevated risk. This is an unconfirmed assessment and may be wrong.", "कई स्थानीय संकेत अधिक जोखिम बताते हैं। यह अपुष्ट आकलन है और गलत भी हो सकता है।", "ਕਈ ਸਥਾਨਕ ਸੰਕੇਤ ਵੱਧ ਜੋਖਮ ਦੱਸਦੇ ਹਨ। ਇਹ ਅਪੁਸ਼ਟ ਅੰਦਾਜ਼ਾ ਹੈ ਅਤੇ ਗਲਤ ਵੀ ਹੋ ਸਕਦਾ ਹੈ।"),
        ReasonCode.BRAND_IN_DOMAIN to listOf("The website name uses a well-known brand, but it is not that brand's official website.", "वेबसाइट के नाम में किसी मशहूर ब्रांड का नाम है, लेकिन यह उसकी आधिकारिक वेबसाइट नहीं है।", "ਵੈੱਬਸਾਈਟ ਦੇ ਨਾਮ ਵਿੱਚ ਕਿਸੇ ਮਸ਼ਹੂਰ ਬ੍ਰਾਂਡ ਦਾ ਨਾਮ ਹੈ, ਪਰ ਇਹ ਉਸ ਦੀ ਅਧਿਕਾਰਤ ਵੈੱਬਸਾਈਟ ਨਹੀਂ ਹੈ।"),
        ReasonCode.PHISHING_KEYWORDS to listOf("The website name contains words scammers use, such as KYC, verify, reward or refund.", "वेबसाइट के नाम में ठगों के इस्तेमाल वाले शब्द हैं, जैसे KYC, verify, इनाम या रिफ़ंड।", "ਵੈੱਬਸਾਈਟ ਦੇ ਨਾਮ ਵਿੱਚ ਠੱਗਾਂ ਵੱਲੋਂ ਵਰਤੇ ਜਾਂਦੇ ਸ਼ਬਦ ਹਨ, ਜਿਵੇਂ KYC, verify, ਇਨਾਮ ਜਾਂ ਰਿਫੰਡ।"),
        ReasonCode.DANGEROUS_DOWNLOAD to listOf("This address or message refers to an app or executable download. Its contents were not inspected; the extension alone does not establish harm.", "इस पते या संदेश में ऐप या चलने वाली फ़ाइल का डाउनलोड है। फ़ाइल की सामग्री नहीं जाँची गई; केवल एक्सटेंशन से नुकसान की पुष्टि नहीं होती।", "ਇਸ ਪਤੇ ਜਾਂ ਸੁਨੇਹੇ ਵਿੱਚ ਐਪ ਜਾਂ ਚੱਲਣ ਵਾਲੀ ਫ਼ਾਈਲ ਦਾ ਡਾਊਨਲੋਡ ਹੈ। ਫ਼ਾਈਲ ਦੀ ਸਮੱਗਰੀ ਨਹੀਂ ਜਾਂਚੀ ਗਈ; ਸਿਰਫ਼ ਐਕਸਟੈਂਸ਼ਨ ਨੁਕਸਾਨ ਦੀ ਪੁਸ਼ਟੀ ਨਹੀਂ ਕਰਦਾ।"),
        ReasonCode.FREE_HOSTING to listOf("The page is on a free website service that anyone can set up in minutes.", "यह पेज मुफ़्त वेबसाइट सेवा पर है, जिसे कोई भी मिनटों में बना सकता है।", "ਇਹ ਪੇਜ ਮੁਫ਼ਤ ਵੈੱਬਸਾਈਟ ਸੇਵਾ ਉੱਤੇ ਹੈ, ਜਿਸ ਨੂੰ ਕੋਈ ਵੀ ਮਿੰਟਾਂ ਵਿੱਚ ਬਣਾ ਸਕਦਾ ਹੈ।"),
        ReasonCode.NONSTANDARD_PORT to listOf("The address uses an unusual network port.", "पते में एक असामान्य नेटवर्क पोर्ट है।", "ਪਤੇ ਵਿੱਚ ਇੱਕ ਅਸਾਧਾਰਨ ਨੈੱਟਵਰਕ ਪੋਰਟ ਹੈ।"),
        ReasonCode.BRAND_MISMATCH to listOf("The message names a bank or company, but the link goes to a different website.", "संदेश में किसी बैंक या कंपनी का नाम है, लेकिन लिंक किसी दूसरी वेबसाइट पर ले जाता है।", "ਸੁਨੇਹੇ ਵਿੱਚ ਕਿਸੇ ਬੈਂਕ ਜਾਂ ਕੰਪਨੀ ਦਾ ਨਾਮ ਹੈ, ਪਰ ਲਿੰਕ ਕਿਸੇ ਹੋਰ ਵੈੱਬਸਾਈਟ ਉੱਤੇ ਲੈ ਜਾਂਦਾ ਹੈ।"),
        ReasonCode.CREDENTIAL_REQUEST to listOf("The message asks for an OTP, PIN, password or card details. Real banks never ask for these.", "संदेश में OTP, PIN, पासवर्ड या कार्ड की जानकारी माँगी गई है। असली बैंक यह कभी नहीं माँगते।", "ਸੁਨੇਹੇ ਵਿੱਚ OTP, PIN, ਪਾਸਵਰਡ ਜਾਂ ਕਾਰਡ ਦੀ ਜਾਣਕਾਰੀ ਮੰਗੀ ਗਈ ਹੈ। ਅਸਲੀ ਬੈਂਕ ਇਹ ਕਦੇ ਨਹੀਂ ਮੰਗਦੇ।"),
        ReasonCode.REMOTE_ACCESS_REQUEST to listOf("The message asks you to install a screen-sharing app such as AnyDesk. That gives a stranger control of your phone.", "संदेश में AnyDesk जैसा स्क्रीन-शेयरिंग ऐप इंस्टॉल करने को कहा गया है। इससे अजनबी आपके फ़ोन पर नियंत्रण पा लेता है।", "ਸੁਨੇਹੇ ਵਿੱਚ AnyDesk ਵਰਗੀ ਸਕ੍ਰੀਨ-ਸ਼ੇਅਰਿੰਗ ਐਪ ਇੰਸਟਾਲ ਕਰਨ ਲਈ ਕਿਹਾ ਗਿਆ ਹੈ। ਇਸ ਨਾਲ ਅਜਨਬੀ ਤੁਹਾਡੇ ਫ਼ੋਨ ਉੱਤੇ ਕਾਬੂ ਪਾ ਲੈਂਦਾ ਹੈ।"),
        ReasonCode.RANDOM_HOSTNAME to listOf("The website name looks randomly generated, which is typical of throwaway scam pages.", "वेबसाइट का नाम बेतरतीब ढंग से बना लगता है, जो कुछ समय के लिए बनाए गए ठगी पेजों में आम है।", "ਵੈੱਬਸਾਈਟ ਦਾ ਨਾਮ ਬੇਤਰਤੀਬ ਢੰਗ ਨਾਲ ਬਣਿਆ ਲੱਗਦਾ ਹੈ, ਜੋ ਥੋੜ੍ਹੇ ਸਮੇਂ ਲਈ ਬਣਾਏ ਠੱਗੀ ਪੇਜਾਂ ਵਿੱਚ ਆਮ ਹੈ।")
    )
    private val priority = listOf(
        ReasonCode.GOOGLE_REPUTATION, ReasonCode.PROVIDER_TEST, ReasonCode.TEST_INPUT, ReasonCode.DEMO_ONLY, ReasonCode.EICAR_TEST,
        ReasonCode.LOCAL_RISK, ReasonCode.KNOWN_PHISHING, ReasonCode.KNOWN_BAD_HASH, ReasonCode.LOOKALIKE,
        ReasonCode.BRAND_IN_SUBDOMAIN, ReasonCode.USER_INFO, ReasonCode.DANGEROUS_DOWNLOAD,
        ReasonCode.REMOTE_ACCESS_REQUEST, ReasonCode.CREDENTIAL_REQUEST, ReasonCode.BRAND_MISMATCH,
        ReasonCode.BRAND_IN_DOMAIN, ReasonCode.PHISHING_KEYWORDS, ReasonCode.SCAM_LANGUAGE, ReasonCode.IP_HOST
    )
    private val trailing = setOf(ReasonCode.REPUTATION_NO_MATCH, ReasonCode.REPUTATION_NOT_CHECKED, ReasonCode.FILE_SCAN)
    /** Strongest, most specific explanation first; provider status notes last. */
    fun ordered(reasons: Collection<ReasonCode>): List<ReasonCode> = reasons.sortedBy {
        val rank = priority.indexOf(it)
        when { it in trailing -> 1_000; rank >= 0 -> rank; else -> 100 }
    }
    fun get(code: ReasonCode, language: String): String = messages.getValue(code)[when (language.substringBefore('-')) { "hi" -> 1; "pa" -> 2; else -> 0 }]
}
