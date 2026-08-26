package io.konekt.client.net

import io.github.youndie.kompot.auth.kompotAuthSerializersModule
import io.github.youndie.kompot.form.standard.formStandardSerializersModule
import io.github.youndie.kompot.generated.generatedFormsSerializersModule
import io.github.youndie.kompot.generated.generatedKonektSerializersModule
import io.github.youndie.kompot.generated.generatedStandardSerializersModule
import io.github.youndie.kompot.kompotCoreSerializersModule
import io.github.youndie.kompot.standard.kompotStandardSerializersModule
import io.konekt.feature.esim.shared.api.esimActionsSerializersModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus

// The client's Json, and it has to be the SERVER'S set of modules or the two disagree about a wire
// they both think they speak.
//
// Assembled here rather than imported from the server, because the server's copy lives in a module
// this one must not see. Two lists that have to match and cannot share a definition is exactly the
// kind of seam that drifts, which is why `ClientAndServerSpeakOneWireTest` compares them by what
// they can decode rather than by reading both.
//
// `ignoreUnknownKeys` on purpose: a field added by a newer server must not take the screen down. The
// discriminator is `"type"`, matching the server's.
val konektClientJson: Json =
    Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        serializersModule =
            kompotCoreSerializersModule +
            kompotStandardSerializersModule +
            generatedStandardSerializersModule +
            generatedKonektSerializersModule +
            kompotAuthSerializersModule +
            esimActionsSerializersModule +
            // BOTH FORM MODULES, and a client with only one of them decodes the screen and then dies
            // on `$.schema.fields[0]` — which is exactly what the stand found the first time it asked
            // for a form. `generatedFormsSerializersModule` carries the form COMPONENTS, the inputs
            // and the read-only field; `formStandardSerializersModule` the FIELD DEFINITIONS, the
            // values and the rules the schema is made of. They are registered separately upstream, so
            // a client that renders a form needs both and needs to say so.
            generatedFormsSerializersModule +
            formStandardSerializersModule
    }
