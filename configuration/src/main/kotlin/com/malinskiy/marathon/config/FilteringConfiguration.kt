package com.malinskiy.marathon.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.malinskiy.marathon.config.exceptions.ConfigurationException
import java.io.File

data class FilteringConfiguration(
    @JsonProperty("allowlist", required = false) val allowlist: Collection<TestFilterConfiguration> = emptyList(),
    @JsonProperty("blocklist", required = false) val blocklist: Collection<TestFilterConfiguration> = emptyList()
)

sealed class TestFilterConfiguration {
    abstract fun validate()
    
    data class SimpleClassnameFilterConfiguration(
        @JsonProperty("regex") val regex: Regex? = null,
        @JsonProperty("values") val values: List<String>? = null,
        @JsonProperty("file") val file: File? = null,
    ) : TestFilterConfiguration() {
        override fun validate() {
            var i = 0
            if (regex != null) i++
            if (values != null) i++
            if (file != null) i++

            if (i > 1) throw ConfigurationException("Only one of [regex,values,file] can be specified for ${this::class.simpleName}")
            if (i == 0) throw ConfigurationException("At least one of [regex,values,file] should be specified for ${this::class.simpleName}")
        }
    }

    data class FullyQualifiedClassnameFilterConfiguration(
        @JsonProperty("regex") val regex: Regex? = null,
        @JsonProperty("values") val values: List<String>? = null,
        @JsonProperty("file") val file: File? = null,
    ) : TestFilterConfiguration() {
        override fun validate() {
            var i = 0
            if (regex != null) i++
            if (values != null) i++
            if (file != null) i++

            if (i > 1) throw ConfigurationException("Only one of [regex,values,file] can be specified for ${this::class.simpleName}")
            if (i == 0) throw ConfigurationException("At least one of [regex,values,file] should be specified for ${this::class.simpleName}")
        }
    }

    data class TestPackageFilterConfiguration(
        @JsonProperty("regex") val regex: Regex? = null,
        @JsonProperty("values") val values: List<String>? = null,
        @JsonProperty("file") val file: File? = null,
    ) : TestFilterConfiguration() {
        override fun validate() {
            var i = 0
            if (regex != null) i++
            if (values != null) i++
            if (file != null) i++

            if (i > 1) throw ConfigurationException("Only one of [regex,values,file] can be specified for ${this::class.simpleName}")
            if (i == 0) throw ConfigurationException("At least one of [regex,values,file] should be specified for ${this::class.simpleName}")
        }
    }

    data class AnnotationDataFilterConfiguration(
        @JsonProperty("nameRegex") val nameRegex: Regex,
        @JsonProperty("valueRegex") val valueRegex: Regex
    ) : TestFilterConfiguration() {
        override fun validate() {
            
        }
    }

    data class FullyQualifiedTestnameFilterConfiguration(
        @JsonProperty("regex") val regex: Regex? = null,
        @JsonProperty("values") val values: List<String>? = null,
        @JsonProperty("file") val file: File? = null,
    ) : TestFilterConfiguration() {
        override fun validate() {
            var i = 0
            if (regex != null) i++
            if (values != null) i++
            if (file != null) i++

            if (i > 1) throw ConfigurationException("Only one of [regex,values,file] can be specified for ${this::class.simpleName}")
            if (i == 0) throw ConfigurationException("At least one of [regex,values,file] should be specified for ${this::class.simpleName}")
        }
    }

    data class FragmentationFilterConfiguration(
        val index: Int, 
        val count: Int,
    ) : TestFilterConfiguration() {
        override fun validate() {
            if (index < 0) throw ConfigurationException("Fragment index [$index] should be >= 0")
            if (count < 0) throw ConfigurationException("Fragment count [$count] should be >= 0")
            if (index >= count) throw ConfigurationException("Fragment index [$index] should be less than count [$count]")
        }
    }

    data class TestMethodFilterConfiguration(
        @JsonProperty("regex") val regex: Regex? = null,
        @JsonProperty("values") val values: List<String>? = null,
        @JsonProperty("file") val file: File? = null,
    ) : TestFilterConfiguration() {
        override fun validate() {
            var i = 0
            if (regex != null) i++
            if (values != null) i++
            if (file != null) i++

            if (i > 1) throw ConfigurationException("Only one of [regex,values,file] can be specified for ${this::class.simpleName}")
            if (i == 0) throw ConfigurationException("At least one of [regex,values,file] should be specified for ${this::class.simpleName}")
        }
    }

    data class AnnotationFilterConfiguration(
        @JsonProperty("regex") val regex: Regex? = null,
        @JsonProperty("values") val values: List<String>? = null,
        @JsonProperty("file") val file: File? = null,
    ) : TestFilterConfiguration() {
        override fun validate() {
            var i = 0
            if (regex != null) i++
            if (values != null) i++
            if (file != null) i++

            if (i > 1) throw ConfigurationException("Only one of [regex,values,file] can be specified for ${this::class.simpleName}")
            if (i == 0) throw ConfigurationException("At least one of [regex,values,file] should be specified for ${this::class.simpleName}")
        }
    }

    data class CompositionFilterConfiguration(
        @JsonProperty("filters") val filters: List<TestFilterConfiguration>,
        @JsonProperty("op") val op: OPERATION
    ) : TestFilterConfiguration() {
        override fun validate() {
            filters.forEach { it.validate() }
        }
    }
}

enum class OPERATION {
    UNION,
    INTERSECTION,
    SUBTRACT
}
