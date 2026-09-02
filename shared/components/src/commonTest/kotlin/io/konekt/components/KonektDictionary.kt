package io.konekt.components

import io.github.youndie.kompot.KompotComponent
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.NavigateAction
import io.github.youndie.kompot.standard.TextComponent
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus

// The dictionary, once, as a list of (wire name, a fully populated instance). Every test below walks
// it, so a component added to `commonMain` without a line here is a component nothing checks — and a
// line here without a component does not compile.
val konektDictionary: List<Pair<String, KompotComponent>> =
    listOf(
        "usage_counter_card" to
            UsageCounterCardComponent(
                id = "counter-data",
                title = "Data",
                valueText = "15,8 GB left",
                captionText = "Minutes run out in about two days at your current pace.",
                progress = 0.62f,
                state = CounterStates.LOW,
                action = NavigateAction("app://usage/data"),
            ),
        "plan_card" to
            PlanCardComponent(
                id = "plan-tr-10",
                title = "Turkey",
                priceText = "$1,190",
                quotaTexts = listOf("10 GB", "30 days"),
                zoneText = "Turkey",
                badgeText = "Popular",
                state = PlanStates.AVAILABLE,
                action = NavigateAction("app://plans/tr-10"),
            ),
        "esim_card" to
            EsimCardComponent(
                id = "esim-8f21",
                label = "Travel line",
                iccid = "8944500000001234567",
                status = EsimStatuses.READY,
                statusText = "Installs as an eSIM by QR code. Your device supports it.",
                action = NavigateAction("app://esim/8f21-4c90"),
            ),
        "esim_qr" to
            EsimQrComponent(
                id = "esim-qr-8f21",
                payload = "LPA:1\$rsp.konekt.io\$8F214C90",
                captionText = "Stay on Wi-Fi. This takes up to a minute and finishes on its own.",
                manualCodeText = "8F21-4C90",
            ),
        "order_row" to
            OrderRowComponent(
                id = "order-5b17",
                reference = "5b17-7702",
                title = "Turkey · 10 GB · 30 days",
                dateText = "26 Jun",
                amountText = "−$450",
                status = OrderStatuses.COMPENSATED,
                statusText = "Reversed",
                noteText = "$450 returned to balance on 28 Jun — profile never activated.",
                action = NavigateAction("app://orders/5b17-7702"),
            ),
        "banner" to
            BannerComponent(
                id = "banner-low-balance",
                text = "Your balance covers four more days at this rate.",
                tone = MessageTones.LOW,
                actionText = "Top up",
                action = NavigateAction("app://balance/top-up"),
            ),
        "snackbar" to
            SnackbarComponent(
                id = "snack-copied",
                text = "Activation code copied.",
                tone = MessageTones.INFO,
            ),
        "step_meter" to
            StepMeterComponent(
                id = "install-progress",
                current = 3,
                total = 4,
                label = "Install eSIM",
            ),
        "skeleton" to
            SkeletonComponent(
                id = "plans-loading",
                shape = SkeletonShapes.CARD,
                count = 3,
            ),
        // Fully populated like every entry here, and that includes `selected` on exactly one item:
        // the round-trip test compares by equality, so a field left at its default is a field this
        // dictionary never checks travels.
        "bottom_nav" to
            BottomNavComponent(
                id = "shell-nav",
                items =
                    listOf(
                        BottomNavItem("Home", NavigateAction("app://home"), selected = true),
                        BottomNavItem("Plans", NavigateAction("app://plans")),
                        BottomNavItem("Orders", NavigateAction("app://orders")),
                        BottomNavItem("Profile", NavigateAction("app://profile")),
                    ),
            ),
        // THE ONE CONTAINER, and its specimen carries children on purpose: a `surface` holding
        // nothing round-trips whatever the children field does, so an empty one would let a
        // serialisation mistake in the only field that distinguishes this from a leaf go unnoticed.
        "surface" to
            SurfaceComponent(
                id = "balance",
                tone = SurfaceTones.ACCENT,
                children =
                    listOf(
                        TextComponent(id = "balance-label", text = "Balance"),
                        TextComponent(id = "balance-amount", text = "$54"),
                    ),
            ),
        "slider_input" to
            SliderInputComponent(
                id = "custom-package-data_gb",
                fieldId = "data_gb",
                label = "Data",
                steps = listOf("0", "1", "5", "10", "20", "50"),
                unit = "GB",
            ),
        "icon" to
            IconComponent(
                id = "purchase-mark",
                icon = VectorIcon(paths = listOf("M5 12l5 5L20 7")),
                tone = MessageTones.INFO,
            ),
    )

// The application's own Json: the toolkit's core and standard sets plus this dictionary. Assembled
// here rather than imported, because assembling it is precisely what an application does and getting
// it wrong is what these tests are for.
val konektTestJson: Json =
    Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        serializersModule =
            kompotCoreSerializersModule +
            kompotStandardSerializersModule +
            generatedStandardSerializersModule +
            generatedKonektSerializersModule
    }
