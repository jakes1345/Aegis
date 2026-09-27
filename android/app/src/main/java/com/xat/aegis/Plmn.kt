package com.xat.aegis

/**
 * MCC/MNC → carrier name, for the operators a user is likely to be camped on. The
 * modem's own `networkOperatorName` is authoritative when it is set; this table is
 * for the cases where it is blank (many roaming and 2G registrations) and for the
 * PLMN row on the Cell tab, where "310-260" alone means nothing to anyone.
 *
 * The country name comes from the MCC and is shown even for an MNC not listed.
 */
object Plmn {

    private val COUNTRIES: Map<String, String> = mapOf(
        "202" to "Greece", "204" to "Netherlands", "206" to "Belgium", "208" to "France",
        "212" to "Monaco", "213" to "Andorra", "214" to "Spain", "216" to "Hungary",
        "218" to "Bosnia", "219" to "Croatia", "220" to "Serbia", "222" to "Italy",
        "226" to "Romania", "228" to "Switzerland", "230" to "Czechia", "231" to "Slovakia",
        "232" to "Austria", "234" to "United Kingdom", "235" to "United Kingdom",
        "238" to "Denmark", "240" to "Sweden", "242" to "Norway", "244" to "Finland",
        "246" to "Lithuania", "247" to "Latvia", "248" to "Estonia", "250" to "Russia",
        "255" to "Ukraine", "257" to "Belarus", "259" to "Moldova", "260" to "Poland",
        "262" to "Germany", "268" to "Portugal", "270" to "Luxembourg", "272" to "Ireland",
        "274" to "Iceland", "276" to "Albania", "278" to "Malta", "280" to "Cyprus",
        "282" to "Georgia", "283" to "Armenia", "284" to "Bulgaria", "286" to "Türkiye",
        "293" to "Slovenia", "294" to "North Macedonia", "297" to "Montenegro",
        "302" to "Canada", "310" to "United States", "311" to "United States",
        "312" to "United States", "313" to "United States", "314" to "United States",
        "316" to "United States", "330" to "Puerto Rico", "334" to "Mexico",
        "338" to "Jamaica", "344" to "Antigua", "350" to "Bermuda", "352" to "Grenada",
        "356" to "St Kitts", "358" to "St Lucia", "360" to "St Vincent", "362" to "Curaçao",
        "363" to "Aruba", "364" to "Bahamas", "365" to "Anguilla", "366" to "Dominica",
        "368" to "Cuba", "370" to "Dominican Rep.", "372" to "Haiti", "374" to "Trinidad",
        "376" to "Turks & Caicos", "400" to "Azerbaijan", "401" to "Kazakhstan",
        "404" to "India", "405" to "India", "410" to "Pakistan", "412" to "Afghanistan",
        "413" to "Sri Lanka", "414" to "Myanmar", "415" to "Lebanon", "416" to "Jordan",
        "417" to "Syria", "418" to "Iraq", "419" to "Kuwait", "420" to "Saudi Arabia",
        "421" to "Yemen", "422" to "Oman", "424" to "UAE", "425" to "Israel", "426" to "Bahrain",
        "427" to "Qatar", "428" to "Mongolia", "429" to "Nepal", "432" to "Iran",
        "434" to "Uzbekistan", "436" to "Tajikistan", "437" to "Kyrgyzstan", "438" to "Turkmenistan",
        "440" to "Japan", "441" to "Japan", "450" to "South Korea", "452" to "Vietnam",
        "454" to "Hong Kong", "455" to "Macau", "456" to "Cambodia", "457" to "Laos",
        "460" to "China", "466" to "Taiwan", "467" to "North Korea", "470" to "Bangladesh",
        "472" to "Maldives", "502" to "Malaysia", "505" to "Australia", "510" to "Indonesia",
        "514" to "Timor-Leste", "515" to "Philippines", "520" to "Thailand", "525" to "Singapore",
        "528" to "Brunei", "530" to "New Zealand", "602" to "Egypt", "603" to "Algeria",
        "604" to "Morocco", "605" to "Tunisia", "606" to "Libya", "608" to "Senegal",
        "610" to "Mali", "611" to "Guinea", "612" to "Côte d'Ivoire", "613" to "Burkina Faso",
        "614" to "Niger", "615" to "Togo", "616" to "Benin", "617" to "Mauritius",
        "620" to "Ghana", "621" to "Nigeria", "622" to "Chad", "623" to "Central African Rep.",
        "624" to "Cameroon", "625" to "Cape Verde", "627" to "Equatorial Guinea", "628" to "Gabon",
        "629" to "Congo", "630" to "DR Congo", "631" to "Angola", "632" to "Guinea-Bissau",
        "633" to "Seychelles", "634" to "Sudan", "635" to "Rwanda", "636" to "Ethiopia",
        "637" to "Somalia", "638" to "Djibouti", "639" to "Kenya", "640" to "Tanzania",
        "641" to "Uganda", "642" to "Burundi", "643" to "Mozambique", "645" to "Zambia",
        "646" to "Madagascar", "647" to "Réunion", "648" to "Zimbabwe", "649" to "Namibia",
        "650" to "Malawi", "651" to "Lesotho", "652" to "Botswana", "653" to "Eswatini",
        "654" to "Comoros", "655" to "South Africa", "657" to "Eritrea", "659" to "South Sudan",
        "702" to "Belize", "704" to "Guatemala", "706" to "El Salvador", "708" to "Honduras",
        "710" to "Nicaragua", "712" to "Costa Rica", "714" to "Panama", "716" to "Peru",
        "722" to "Argentina", "724" to "Brazil", "730" to "Chile", "732" to "Colombia",
        "734" to "Venezuela", "736" to "Bolivia", "738" to "Guyana", "740" to "Ecuador",
        "744" to "Paraguay", "746" to "Suriname", "748" to "Uruguay"
    )

    /**
     * Keyed on "MCC-MNC". Two-digit MNCs are stored as two digits, three as three;
     * the lookup tries the caller's MNC as given, then zero-padded to three, then
     * trimmed to two, because modems disagree about padding.
     */
    private val CARRIERS: Map<String, String> = mapOf(
        // United States. AT&T and Verizon own many PLMNs; T-Mobile absorbed Sprint's.
        "310-030" to "AT&T", "310-070" to "AT&T", "310-080" to "AT&T", "310-090" to "AT&T",
        "310-150" to "AT&T", "310-170" to "AT&T", "310-280" to "AT&T", "310-380" to "AT&T",
        "310-410" to "AT&T", "310-560" to "AT&T", "310-680" to "AT&T", "310-950" to "AT&T",
        "311-180" to "AT&T", "313-100" to "FirstNet (AT&T)",
        "310-004" to "Verizon", "310-005" to "Verizon", "310-006" to "Verizon", "310-010" to "Verizon",
        "310-012" to "Verizon", "310-013" to "Verizon", "310-590" to "Verizon", "310-890" to "Verizon",
        "310-910" to "Verizon", "311-110" to "Verizon", "311-270" to "Verizon", "311-271" to "Verizon",
        "311-272" to "Verizon", "311-273" to "Verizon", "311-274" to "Verizon", "311-275" to "Verizon",
        "311-276" to "Verizon", "311-277" to "Verizon", "311-278" to "Verizon", "311-279" to "Verizon",
        "311-280" to "Verizon", "311-281" to "Verizon", "311-282" to "Verizon", "311-283" to "Verizon",
        "311-284" to "Verizon", "311-285" to "Verizon", "311-286" to "Verizon", "311-287" to "Verizon",
        "311-288" to "Verizon", "311-289" to "Verizon", "311-390" to "Verizon", "311-480" to "Verizon",
        "311-481" to "Verizon", "311-482" to "Verizon", "311-483" to "Verizon", "311-484" to "Verizon",
        "311-485" to "Verizon", "311-486" to "Verizon", "311-487" to "Verizon", "311-488" to "Verizon",
        "311-489" to "Verizon",
        "310-160" to "T-Mobile US", "310-200" to "T-Mobile US", "310-210" to "T-Mobile US",
        "310-220" to "T-Mobile US", "310-230" to "T-Mobile US", "310-240" to "T-Mobile US",
        "310-250" to "T-Mobile US", "310-260" to "T-Mobile US", "310-270" to "T-Mobile US",
        "310-300" to "T-Mobile US", "310-310" to "T-Mobile US", "310-490" to "T-Mobile US",
        "310-660" to "T-Mobile US", "310-800" to "T-Mobile US", "311-660" to "T-Mobile US",
        "310-120" to "Sprint (T-Mobile)", "311-490" to "Sprint (T-Mobile)", "311-870" to "Sprint (T-Mobile)",
        "311-880" to "Sprint (T-Mobile)", "312-530" to "Sprint (T-Mobile)",
        "311-580" to "US Cellular", "311-581" to "US Cellular", "311-582" to "US Cellular",
        "311-583" to "US Cellular", "311-584" to "US Cellular", "311-585" to "US Cellular",
        "311-586" to "US Cellular", "311-587" to "US Cellular", "311-588" to "US Cellular",
        "311-589" to "US Cellular",
        "312-770" to "DISH Wireless", "313-340" to "DISH Wireless", "313-350" to "DISH Wireless",
        "310-100" to "Plateau Wireless", "311-040" to "Choice Wireless", "311-230" to "C Spire",
        "310-370" to "Docomo Pacific", "310-400" to "iConnect (Guam)", "310-450" to "Viaero",
        "310-540" to "Oklahoma Western", "310-580" to "Inland Cellular", "310-600" to "Cellcom",
        "311-030" to "Indigo Wireless", "311-070" to "AT&T", "311-100" to "Nex-Tech",
        "311-190" to "Cellular Properties", "311-370" to "GCI (Alaska)", "311-670" to "Pine Belt",
        "311-690" to "TeleBEEPER", "312-190" to "Standing Rock Telecom", "312-420" to "Nex-Tech",
        "313-000" to "Tampnet", "313-460" to "AT&T (Mexico)",
        // Canada
        "302-220" to "Telus", "302-221" to "Telus", "302-320" to "Rogers", "302-370" to "Fido (Rogers)",
        "302-500" to "Videotron", "302-510" to "Videotron", "302-610" to "Bell", "302-640" to "Bell",
        "302-720" to "Rogers", "302-780" to "SaskTel", "302-880" to "Bell/Telus shared",
        "302-490" to "Freedom Mobile", "302-660" to "MTS", "302-690" to "Bell", "302-270" to "Eastlink",
        // Mexico
        "334-020" to "Telcel", "334-030" to "Movistar", "334-050" to "AT&T Mexico", "334-090" to "AT&T Mexico",
        "334-140" to "Altán Redes",
        // United Kingdom & Ireland
        "234-10" to "O2 UK", "234-15" to "Vodafone UK", "234-20" to "Three UK", "234-30" to "EE",
        "234-33" to "EE", "234-34" to "EE", "234-38" to "Virgin Mobile UK", "234-50" to "JT (Jersey)",
        "234-55" to "Sure (Guernsey)", "234-58" to "Manx Telecom", "234-76" to "BT",
        "272-01" to "Vodafone IE", "272-02" to "Three IE", "272-03" to "Eir", "272-05" to "Three IE",
        // Germany
        "262-01" to "Telekom DE", "262-02" to "Vodafone DE", "262-03" to "O2 DE", "262-06" to "Telekom DE",
        "262-07" to "O2 DE", "262-08" to "O2 DE", "262-09" to "Vodafone DE", "262-23" to "1&1",
        // France
        "208-01" to "Orange FR", "208-02" to "Orange FR", "208-10" to "SFR", "208-13" to "SFR",
        "208-15" to "Free Mobile", "208-16" to "Free Mobile", "208-20" to "Bouygues", "208-21" to "Bouygues",
        // Spain, Italy, Portugal
        "214-01" to "Vodafone ES", "214-03" to "Orange ES", "214-04" to "Yoigo", "214-07" to "Movistar",
        "222-01" to "TIM", "222-10" to "Vodafone IT", "222-50" to "Iliad", "222-88" to "WindTre",
        "268-01" to "Vodafone PT", "268-03" to "NOS", "268-06" to "MEO",
        // Benelux, Nordics, Alps
        "204-04" to "Vodafone NL", "204-08" to "KPN", "204-16" to "T-Mobile NL", "204-20" to "Odido",
        "206-01" to "Proximus", "206-10" to "Orange BE", "206-20" to "Base",
        "238-01" to "TDC", "238-02" to "Telenor DK", "238-06" to "3 DK", "238-20" to "Telia DK",
        "240-01" to "Telia SE", "240-02" to "3 SE", "240-07" to "Tele2 SE", "240-08" to "Telenor SE",
        "242-01" to "Telenor NO", "242-02" to "Telia NO", "242-14" to "Ice",
        "244-05" to "Elisa", "244-12" to "DNA", "244-91" to "Telia FI",
        "228-01" to "Swisscom", "228-02" to "Sunrise", "228-03" to "Salt",
        "232-01" to "A1", "232-03" to "Magenta AT", "232-05" to "Drei AT",
        // Central & Eastern Europe
        "260-01" to "Plus", "260-02" to "T-Mobile PL", "260-03" to "Orange PL", "260-06" to "Play",
        "230-01" to "T-Mobile CZ", "230-02" to "O2 CZ", "230-03" to "Vodafone CZ",
        "216-01" to "Yettel HU", "216-30" to "Telekom HU", "216-70" to "Vodafone HU",
        "226-01" to "Vodafone RO", "226-03" to "Telekom RO", "226-10" to "Orange RO",
        "202-01" to "Cosmote", "202-05" to "Vodafone GR", "202-10" to "Nova",
        "286-01" to "Turkcell", "286-02" to "Vodafone TR", "286-03" to "Türk Telekom",
        "250-01" to "MTS RU", "250-02" to "MegaFon", "250-20" to "Tele2 RU", "250-99" to "Beeline RU",
        "255-01" to "Vodafone UA", "255-03" to "Kyivstar", "255-06" to "lifecell",
        // Middle East & Africa
        "425-01" to "Partner", "425-02" to "Cellcom IL", "425-03" to "Pelephone",
        "424-02" to "Etisalat", "424-03" to "du", "420-01" to "STC", "420-03" to "Mobily", "420-04" to "Zain SA",
        "602-01" to "Orange EG", "602-02" to "Vodafone EG", "602-03" to "Etisalat EG",
        "655-01" to "Vodacom", "655-07" to "Cell C", "655-10" to "MTN SA",
        "621-20" to "Airtel NG", "621-30" to "MTN NG", "621-50" to "Glo", "639-02" to "Safaricom",
        // Asia-Pacific
        "404-45" to "Airtel IN", "405-854" to "Jio", "404-20" to "Vi (Vodafone Idea)",
        "440-10" to "NTT Docomo", "440-20" to "SoftBank", "440-50" to "KDDI au", "440-51" to "KDDI au",
        "441-10" to "NTT Docomo", "440-11" to "Rakuten Mobile",
        "450-05" to "SK Telecom", "450-06" to "LG U+", "450-08" to "KT",
        "460-00" to "China Mobile", "460-01" to "China Unicom", "460-03" to "China Telecom",
        "460-11" to "China Telecom", "460-15" to "China Broadnet",
        "466-01" to "FarEasTone", "466-92" to "Chunghwa", "466-97" to "Taiwan Mobile",
        "454-00" to "CSL", "454-03" to "3 HK", "454-06" to "SmarTone", "454-12" to "China Mobile HK",
        "525-01" to "Singtel", "525-03" to "M1", "525-05" to "StarHub",
        "502-12" to "Maxis", "502-13" to "Celcom", "502-16" to "Digi", "502-18" to "U Mobile",
        "520-03" to "AIS", "520-05" to "dtac", "520-04" to "TrueMove H",
        "515-02" to "Globe", "515-03" to "Smart", "515-66" to "DITO",
        "510-01" to "Indosat", "510-10" to "Telkomsel", "510-11" to "XL Axiata",
        "452-01" to "MobiFone", "452-02" to "Vinaphone", "452-04" to "Viettel",
        "505-01" to "Telstra", "505-02" to "Optus", "505-03" to "Vodafone AU",
        "530-01" to "One NZ", "530-05" to "Spark", "530-24" to "2degrees",
        // Latin America
        "724-02" to "TIM BR", "724-05" to "Claro BR", "724-06" to "Vivo", "724-10" to "Vivo", "724-11" to "Vivo",
        "722-07" to "Movistar AR", "722-310" to "Claro AR", "722-340" to "Personal",
        "730-01" to "Entel CL", "730-02" to "Movistar CL", "730-03" to "Claro CL", "730-09" to "WOM",
        "732-101" to "Claro CO", "732-103" to "Tigo CO", "732-123" to "Movistar CO",
        "716-06" to "Movistar PE", "716-10" to "Claro PE", "716-17" to "Entel PE"
    )

    fun country(mcc: String?): String? = mcc?.let { COUNTRIES[it] }

    /** Carrier name for the PLMN, or null when the table has no entry. */
    fun carrier(mcc: String?, mnc: String?): String? {
        if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) return null
        val candidates = linkedSetOf(mnc, mnc.padStart(3, '0'), mnc.trimStart('0').padStart(2, '0'))
        if (mnc.length == 3 && mnc.startsWith("0")) candidates.add(mnc.substring(1))
        for (c in candidates) CARRIERS["$mcc-$c"]?.let { return it }
        return null
    }

    /** "T-Mobile US · United States", "United States (MNC 999)" or null when both unknown. */
    fun describe(mcc: String?, mnc: String?): String? {
        val carrier = carrier(mcc, mnc)
        val country = country(mcc)
        return when {
            carrier != null && country != null -> "$carrier · $country"
            carrier != null -> carrier
            country != null && mnc != null -> "$country (MNC $mnc)"
            country != null -> country
            else -> null
        }
    }
}
