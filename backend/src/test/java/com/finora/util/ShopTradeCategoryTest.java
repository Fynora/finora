package com.finora.util;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import static com.finora.entity.Transaction.Type.EXPENSE;
import static com.finora.entity.Transaction.Type.INCOME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payments to small shops whose payee name says what kind of shop they are. Every narration below
 * is synthetic, shaped after the merchant-QR layouts measured on the real statement corpus.
 */
class ShopTradeCategoryTest {

    private static String of(String description, Transaction.Type direction) {
        return ShopTradeCategory.of(description, direction).orElse(null);
    }

    private static String hyphenUpi(String payee) {
        return "UPI-" + payee + "-Q000000000@YBL-YESB0XXXXXX-000000000000-PAYMENT FROM PHONE";
    }

    @Test
    void aChemistOrMedicalStore_isHealth() {
        assertThat(of(hyphenUpi("SAMPLE MEDICAL"), EXPENSE)).isEqualTo("Health");
        assertThat(of(hyphenUpi("SAMPLE MEDICALS GEN"), EXPENSE)).isEqualTo("Health");
        assertThat(of(hyphenUpi("SAMPLE MEDICO"), EXPENSE)).isEqualTo("Health");
        assertThat(of(hyphenUpi("SAMPLE CHEMIST"), EXPENSE)).isEqualTo("Health");
    }

    @Test
    void anEatery_isDining() {
        assertThat(of(hyphenUpi("SAMPLE NASHTA HOUSE"), EXPENSE)).isEqualTo("Dining");
        assertThat(of(hyphenUpi("SAMPLE DHABA"), EXPENSE)).isEqualTo("Dining");
        assertThat(of(hyphenUpi("SAMPLE CATERS"), EXPENSE)).isEqualTo("Dining");
        assertThat(of(hyphenUpi("SAMPLE SNACKS"), EXPENSE)).isEqualTo("Dining");
        assertThat(of(hyphenUpi("SAMPLE JUICE CENTRE"), EXPENSE)).isEqualTo("Dining");
        assertThat(of(hyphenUpi("SAMPLE KITCHEN"), EXPENSE)).isEqualTo("Dining");
        assertThat(of(hyphenUpi("SAMPLE BAKERY"), EXPENSE)).isEqualTo("Dining");
        assertThat(of(hyphenUpi("SAMPLE TEA STALL"), EXPENSE)).isEqualTo("Dining");
        assertThat(of(hyphenUpi("SAMPLE SWEETS"), EXPENSE)).isEqualTo("Dining");
    }

    /** Sid, 2026-10-02: a small "HOTEL <name>" paid by shop QR is an eatery. */
    @Test
    void aHotelPaidByShopQr_isDining() {
        assertThat(of(hyphenUpi("HOTEL SAMPLE"), EXPENSE)).isEqualTo("Dining");
    }

    /** Measured shape: a restaurant whose name ends in a meat dish is still a restaurant. */
    @Test
    void aHotelNamedAfterAMeatDish_isDiningNotGroceries() {
        assertThat(of("UPI/000000000000/ MS HOTEL SAMPLE MUTTON THALI/PAYTMQR000000@PTYS/UPI/000000000000/", EXPENSE))
                .isEqualTo("Dining");
    }

    @Test
    void aGroceryShop_isGroceries() {
        assertThat(of(hyphenUpi("SAMPLE GENERAL STORES"), EXPENSE)).isEqualTo("Groceries");
        assertThat(of(hyphenUpi("SAMPLE PROVISION"), EXPENSE)).isEqualTo("Groceries");
        assertThat(of(hyphenUpi("SAMPLE KIRANA"), EXPENSE)).isEqualTo("Groceries");
        assertThat(of(hyphenUpi("SAMPLE MART"), EXPENSE)).isEqualTo("Groceries");
        assertThat(of(hyphenUpi("SAMPLE DAIRY"), EXPENSE)).isEqualTo("Groceries");
        assertThat(of(hyphenUpi("SAMPLE MUTTON SHOP"), EXPENSE)).isEqualTo("Groceries");
    }

    @Test
    void aFuelPumpOrVehicleWorkshop_isTransport() {
        assertThat(of(hyphenUpi("SAMPLE PETROLEUM"), EXPENSE)).isEqualTo("Transport");
        assertThat(of(hyphenUpi("SAMPLE AUTO CARE CENTRE"), EXPENSE)).isEqualTo("Transport");
        // The bank cut the name at fifteen characters.
        assertThat(of("UPI/SAMPLE AUTO CENTR/000000000000/UPI", EXPENSE)).isEqualTo("Transport");
    }

    @Test
    void aHardwareOrFurnitureShop_isHomeAndFurnishing() {
        assertThat(of(hyphenUpi("SAMPLE HARDWARE AND"), EXPENSE)).isEqualTo("Home & Furnishing");
        assertThat(of(hyphenUpi("SAMPLE FURNITURE"), EXPENSE)).isEqualTo("Home & Furnishing");
    }

    /** Sid, 2026-10-02: beauty parlours and hair salons get their own "Personal Care" category. */
    @Test
    void aSalonOrBeautyParlour_isPersonalCare() {
        assertThat(of(hyphenUpi("SAMPLE BEAUTY PA"), EXPENSE)).isEqualTo(ShopTradeCategory.PERSONAL_CARE);
        assertThat(of(hyphenUpi("SAMPLE HAIR STUDIO"), EXPENSE)).isEqualTo("Personal Care");
        assertThat(of(hyphenUpi("SAMPLE UNISEX SALON"), EXPENSE)).isEqualTo("Personal Care");
    }

    // --- Precision traps measured on the corpus ---

    /** A clinic doing hair transplants is a medical service, not a salon. */
    @Test
    void aHairTransplantClinic_isNotPersonalCare() {
        assertThat(of("UPI/000000000000/ SAMPLE INSTITUTE OF HAIR TRANSPLANT PVT LTD/SAMPLE/UPI/000000000000/", EXPENSE))
                .isNull();
    }

    /** A digital company named "labs" is not a pathology lab. */
    @Test
    void aCompanyNamedLabs_isNotHealth() {
        assertThat(of(hyphenUpi("SAMPLE DIGITAL LABS P"), EXPENSE)).isNull();
    }

    /** Words that name a business but not what it sells. */
    @Test
    void genericBusinessWords_decideNothing() {
        assertThat(of(hyphenUpi("SAMPLE ENTERPRISES"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE TRADERS"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE STORE"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE FOODS PRIVATE LIMITED"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE MOTORS"), EXPENSE)).isNull();
    }

    /** A bare "auto" is also the bank's own "auto debit" -- a loan or premium mandate. */
    @Test
    void anAutoDebit_isNotTransport() {
        assertThat(of("ACH D- SAMPLE AUTO DEBIT-000000000000", EXPENSE)).isNull();
    }

    /** A note typed by the payer ("Milk", "Furniture") is not the payee's trade. */
    @Test
    void theNoteOnAPayment_isNotRead() {
        assertThat(of("SAMPLEPAYEE UPI/SAMPLEPAYEE/Q000000000@ybl/Furniture/YES BANK L/000000000000/IBL000000/", EXPENSE))
                .isNull();
    }

    /** A college or school named for its subject is paid fees, not a shop's bill: "MEDICAL COLLEGE"
     *  is education or a hospital, and the name cannot say which. */
    @Test
    void aCollegeOrSchoolNamedForItsSubject_decidesNothing() {
        assertThat(of(hyphenUpi("SAMPLE MEDICAL COLLEGE"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE HOTEL MANAGEMENT ACADEMY"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE BEAUTY SCHOOL"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE UNIVERSITY MEDICAL"), EXPENSE)).isNull();
        // Past the payee's first four words, which are all the trade words are read from.
        assertThat(of(hyphenUpi("SHRI SAMPLE STATE MEDICAL COLLEGE AND HOSPITAL"), EXPENSE)).isNull();
    }

    /** A supplier of a trade's equipment is not that trade. */
    @Test
    void anEquipmentOrApplianceSeller_isNotTheTradeItSupplies() {
        assertThat(of(hyphenUpi("SAMPLE KITCHEN APPLIANCES"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE HOTEL EQUIPMENT"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE BAKERY EQUIPMENTS"), EXPENSE)).isNull();
    }

    /** "Mart" is any kind of shop; only a mart that names no other merchandise reads as a grocer. */
    @Test
    void aMartNamedForOtherMerchandise_isNotGroceries() {
        assertThat(of(hyphenUpi("SAMPLE MOBILE MART"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE SHOE MART"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE FASHION MART"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE ELECTRONICS MART"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE MEGA MART"), EXPENSE)).isNull();
    }

    /** A car or pet "salon" grooms a car or a pet, not a person. */
    @Test
    void aCarOrPetSalon_isNotPersonalCare() {
        assertThat(of(hyphenUpi("SAMPLE CAR SALON"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE PET SALON"), EXPENSE)).isNull();
    }

    /** A specific trade decides before the generic "mart". */
    @Test
    void aMartNamedForItsTrade_followsTheTrade() {
        assertThat(of(hyphenUpi("SAMPLE FURNITURE MART"), EXPENSE)).isEqualTo("Home & Furnishing");
        assertThat(of(hyphenUpi("SAMPLE MEDICAL MART"), EXPENSE)).isEqualTo("Health");
    }

    @Test
    void wordsInsideOtherWords_doNotMatch() {
        assertThat(of(hyphenUpi("SAMPLE SMART SOLUTIONS"), EXPENSE)).isNull();
        assertThat(of(hyphenUpi("SAMPLE HOTELIER ACADEMY"), EXPENSE)).isNull();
    }

    // --- Direction ---

    @Test
    void moneyComingIn_isNeverAPurchase() {
        assertThat(of(hyphenUpi("SAMPLE MEDICAL"), INCOME)).isNull();
    }

    @Test
    void noDirection_decidesNothing() {
        assertThat(of(hyphenUpi("SAMPLE MEDICAL"), null)).isNull();
    }

    @Test
    void blankOrNull_decidesNothing() {
        assertThat(of(null, EXPENSE)).isNull();
        assertThat(of("  ", EXPENSE)).isNull();
    }
}
