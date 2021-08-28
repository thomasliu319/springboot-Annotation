/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.autoconfigureprocessor;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Annotation processor to store certain annotations from auto-configuration classes in a
 * property file.
 *
 * @author Madhura Bhave
 * @author Phillip Webb
 * @since 1.5.0
 */
@SupportedAnnotationTypes({ "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
		"org.springframework.boot.autoconfigure.condition.ConditionalOnBean",
		"org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate",
		"org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication",
		"org.springframework.boot.autoconfigure.AutoConfigureBefore",
		"org.springframework.boot.autoconfigure.AutoConfigureAfter",
		"org.springframework.boot.autoconfigure.AutoConfigureOrder" })
public class AutoConfigureAnnotationProcessor extends AbstractProcessor {

	protected static final String PROPERTIES_PATH = "META-INF/spring-autoconfigure-metadata.properties";

	/**
	 * 注解名和全类名的映射
	 *
	 * KEY：注解名
	 * VALUE：全类名
	 */
	private final Map<String, String> annotations;

	/**
	 * 注解名和 ValueExtractor 的映射
	 *
	 * KEY：注解名
	 */
	private final Map<String, ValueExtractor> valueExtractors;

	/**
	 * 扫描和处理注解（Annotation），生成的 Properties 对象
	 */
	private final Map<String, String> properties = new TreeMap<>();

	public AutoConfigureAnnotationProcessor() {
		// <1> 初始化 annotations 属性
		Map<String, String> annotations = new LinkedHashMap<>();
		addAnnotations(annotations);
		this.annotations = Collections.unmodifiableMap(annotations);
		// <2> 初始化 valueExtractors属性
		Map<String, ValueExtractor> valueExtractors = new LinkedHashMap<>();
		addValueExtractors(valueExtractors);
		this.valueExtractors = Collections.unmodifiableMap(valueExtractors);
	}

	/**
	 * annotations 属性，注解名和全类名的映射。
	 * 在 <1> 处，调用 #addAnnotations(Map<String, String> annotations) 方法，进行初始化。
	 * @param annotations
	 */
	protected void addAnnotations(Map<String, String> annotations) {
		// 条件
		annotations.put("ConditionalOnClass", "org.springframework.boot.autoconfigure.condition.ConditionalOnClass");
		annotations.put("ConditionalOnBean", "org.springframework.boot.autoconfigure.condition.ConditionalOnBean");
		annotations.put("ConditionalOnSingleCandidate",
				"org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate");
		annotations.put("ConditionalOnWebApplication",
				"org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication");
		// 排序
		annotations.put("AutoConfigureBefore", "org.springframework.boot.autoconfigure.AutoConfigureBefore");
		annotations.put("AutoConfigureAfter", "org.springframework.boot.autoconfigure.AutoConfigureAfter");
		annotations.put("AutoConfigureOrder", "org.springframework.boot.autoconfigure.AutoConfigureOrder");
	}

	/**
	 * valueExtractors 属性，注解名和 ValueExtractor 的映射。
	 * 在 <2> 处，调用 #addValueExtractors(Map<String, ValueExtractor> attributes) 方法，进行初始化。
	 * @param attributes
	 */
	private void addValueExtractors(Map<String, ValueExtractor> attributes) {

		attributes.put("ConditionalOnClass", new OnClassConditionValueExtractor());
		attributes.put("ConditionalOnBean", new OnBeanConditionValueExtractor());
		attributes.put("ConditionalOnSingleCandidate", new OnBeanConditionValueExtractor());
		attributes.put("ConditionalOnWebApplication", ValueExtractor.allFrom("type"));
		attributes.put("AutoConfigureBefore", ValueExtractor.allFrom("value", "name"));
		attributes.put("AutoConfigureAfter", ValueExtractor.allFrom("value", "name"));
		attributes.put("AutoConfigureOrder", ValueExtractor.allFrom("value"));
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		for (Map.Entry<String, String> entry : this.annotations.entrySet()) {
			process(roundEnv, entry.getKey(), entry.getValue());
		}
		if (roundEnv.processingOver()) {
			try {
				writeProperties();
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to write metadata", ex);
			}
		}
		return false;
	}

	private void process(RoundEnvironment roundEnv, String propertyKey, String annotationName) {
		TypeElement annotationType = this.processingEnv.getElementUtils().getTypeElement(annotationName);
		if (annotationType != null) {
			for (Element element : roundEnv.getElementsAnnotatedWith(annotationType)) {
				processElement(element, propertyKey, annotationName);
			}
		}
	}

	private void processElement(Element element, String propertyKey, String annotationName) {
		try {
			String qualifiedName = Elements.getQualifiedName(element);
			AnnotationMirror annotation = getAnnotation(element, annotationName);
			if (qualifiedName != null && annotation != null) {
				List<Object> values = getValues(propertyKey, annotation);
				this.properties.put(qualifiedName + "." + propertyKey, toCommaDelimitedString(values));
				this.properties.put(qualifiedName, "");
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("Error processing configuration meta-data on " + element, ex);
		}
	}

	private AnnotationMirror getAnnotation(Element element, String type) {
		if (element != null) {
			for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
				if (type.equals(annotation.getAnnotationType().toString())) {
					return annotation;
				}
			}
		}
		return null;
	}

	private String toCommaDelimitedString(List<Object> list) {
		StringBuilder result = new StringBuilder();
		for (Object item : list) {
			result.append((result.length() != 0) ? "," : "");
			result.append(item);
		}
		return result.toString();
	}

	private List<Object> getValues(String propertyKey, AnnotationMirror annotation) {
		ValueExtractor extractor = this.valueExtractors.get(propertyKey);
		if (extractor == null) {
			return Collections.emptyList();
		}
		return extractor.getValues(annotation);
	}

	private void writeProperties() throws IOException {
		if (!this.properties.isEmpty()) {
			Filer filer = this.processingEnv.getFiler();
			FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PROPERTIES_PATH);
			try (Writer writer = new OutputStreamWriter(file.openOutputStream(), StandardCharsets.UTF_8)) {
				for (Map.Entry<String, String> entry : this.properties.entrySet()) {
					writer.append(entry.getKey());
					writer.append("=");
					writer.append(entry.getValue());
					writer.append(System.lineSeparator());
				}
			}
		}
	}

	/**
	 * ValueExtractor ，是 AutoConfigureAnnotationProcessor 的内部接口，值提取器接口。
	 */
	@FunctionalInterface
	private interface ValueExtractor {

		/**
		 * 从注解中，获得对应的值的数组
		 *
		 * @param annotation 注解
		 * @return 值的数组
		 */
		List<Object> getValues(AnnotationMirror annotation);

		/**
		 * 创建 NamedValuesExtractor 对象
		 * @param names 从注解的指定 names 中，提取值们
		 * @return NamedValuesExtractor 对象
		 */
		static ValueExtractor allFrom(String... names) {
			return new NamedValuesExtractor(names);
		}

	}

	/**
	 *AbstractValueExtractor ，是 AutoConfigureAnnotationProcessor 的内部类，
	 *实现 ValueExtractor 接口，ValueExtractor 抽象实现类。
	 *提供了从 AnnotationValue 读取值的公用方法
	 */
	private abstract static class AbstractValueExtractor implements ValueExtractor {


		@SuppressWarnings("unchecked")
		protected Stream<Object> extractValues(AnnotationValue annotationValue) {
			//注解值为空返回空
			if (annotationValue == null) {
				return Stream.empty();
			}
			Object value = annotationValue.getValue();
			//注解值为数组，遍历数组，逐个获取值
			if (value instanceof List) {
				return ((List<AnnotationValue>) value).stream()
						.map((annotation) -> extractValue(annotation.getValue()));
			}
			//注解值非数组，直接提取值
			return Stream.of(extractValue(value));
		}


		private Object extractValue(Object value) {
			if (value instanceof DeclaredType) {
				return Elements.getQualifiedName(((DeclaredType) value).asElement());
			}
			return value;
		}

	}

	/**
	 * NamedValuesExtractor ，是 AutoConfigureAnnotationProcessor 的内部类，
	 * 继承 AbstractValueExtractor 抽象类，读取 names 的 ValueExtractor 实现类。
	 */
	private static class NamedValuesExtractor extends AbstractValueExtractor {

		private final Set<String> names;

		NamedValuesExtractor(String... names) {
			this.names = new HashSet<>(Arrays.asList(names));
		}

		@Override
		public List<Object> getValues(AnnotationMirror annotation) {
			List<Object> result = new ArrayList<>();
			// 遍历 names 数组，读取 name 对应的值，添加到 result 中
			annotation.getElementValues().forEach((key, value) -> {
				if (this.names.contains(key.getSimpleName().toString())) {
					extractValues(value).forEach(result::add);
				}
			});
			return result;
		}

	}

	private static class OnBeanConditionValueExtractor extends AbstractValueExtractor {

		@Override
		public List<Object> getValues(AnnotationMirror annotation) {
			Map<String, AnnotationValue> attributes = new LinkedHashMap<>();
			annotation.getElementValues()
					.forEach((key, value) -> attributes.put(key.getSimpleName().toString(), value));
			if (attributes.containsKey("name")) {
				return Collections.emptyList();
			}
			List<Object> result = new ArrayList<>();
			extractValues(attributes.get("value")).forEach(result::add);
			extractValues(attributes.get("type")).forEach(result::add);
			return result;
		}

	}

	private static class OnClassConditionValueExtractor extends NamedValuesExtractor {

		OnClassConditionValueExtractor() {
			super("value", "name");
		}

		@Override
		public List<Object> getValues(AnnotationMirror annotation) {
			List<Object> values = super.getValues(annotation);
			values.sort(this::compare);
			return values;
		}

		private int compare(Object o1, Object o2) {
			return Comparator.comparing(this::isSpringClass).thenComparing(String.CASE_INSENSITIVE_ORDER)
					.compare(o1.toString(), o2.toString());
		}

		private boolean isSpringClass(String type) {
			return type.startsWith("org.springframework");
		}

	}

}
