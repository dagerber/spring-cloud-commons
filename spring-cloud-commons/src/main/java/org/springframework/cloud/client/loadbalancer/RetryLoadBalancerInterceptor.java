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

package org.springframework.cloud.client.loadbalancer;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;

/**
 * @author Ryan Baxter
 * @author Will Tran
 * @author Gang Li
 */
public class RetryLoadBalancerInterceptor implements ClientHttpRequestInterceptor {

	private LoadBalancerClient loadBalancer;

	private final LoadBalancerProperties properties;

	private LoadBalancerRequestFactory requestFactory;

	private LoadBalancedRetryFactory lbRetryFactory;

	private LoadBalancerRetryProperties retryProperties;

	private final ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;

	public RetryLoadBalancerInterceptor(LoadBalancerClient loadBalancer,
			LoadBalancerRetryProperties retryProperties,
			LoadBalancerRequestFactory requestFactory,
			LoadBalancedRetryFactory lbRetryFactory, LoadBalancerProperties properties,
			// TODO make sure it's provided in config
			ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory) {
		this.loadBalancer = loadBalancer;
		this.retryProperties = retryProperties;
		this.requestFactory = requestFactory;
		this.lbRetryFactory = lbRetryFactory;
		this.properties = properties;
		this.loadBalancerFactory = loadBalancerFactory;
	}

	@Override
	public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
			final ClientHttpRequestExecution execution) throws IOException {
		final URI originalUri = request.getURI();
		final String serviceName = originalUri.getHost();

		Assert.state(serviceName != null,
				"Request URI does not contain a valid hostname: " + originalUri);
		final LoadBalancedRetryPolicy retryPolicy = this.lbRetryFactory
				.createRetryPolicy(serviceName, this.loadBalancer);
		RetryTemplate template = createRetryTemplate(serviceName, request, retryPolicy);
		return template.execute(context -> {
			ServiceInstance serviceInstance = null;
			if (context instanceof LoadBalancedRetryContext) {
				LoadBalancedRetryContext lbContext = (LoadBalancedRetryContext) context;
				serviceInstance = lbContext.getServiceInstance();
			}
			Set<LoadBalancerLifecycle> supportedLifecycleProcessors = supportedLifecycleProcessors(
					serviceName);
			DefaultRequest<HttpRequestContext> lbRequest = new DefaultRequest<>(
					new HttpRequestContext(request, properties.getHint()));
			supportedLifecycleProcessors
					.forEach(lifecycle -> lifecycle.onStart(lbRequest));
			if (serviceInstance == null) {
				serviceInstance = this.loadBalancer.choose(serviceName, lbRequest);
			}
			Response<ServiceInstance> lbResponse = new DefaultResponse(serviceInstance);
			ClientHttpResponse response = RetryLoadBalancerInterceptor.this.loadBalancer
					.execute(serviceName, serviceInstance,
							this.requestFactory.createRequest(request, body, execution));
			int statusCode = response.getRawStatusCode();
			if (retryPolicy != null && retryPolicy.retryableStatusCode(statusCode)) {
				byte[] bodyCopy = StreamUtils.copyToByteArray(response.getBody());
				response.close();
				ClientHttpResponseStatusCodeException clientHttpResponseStatusCodeException = new ClientHttpResponseStatusCodeException(
						serviceName, response, bodyCopy);
				supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onComplete(
						new CompletionContext<ClientHttpResponse, ServiceInstance>(
								CompletionContext.Status.FAILED,
								clientHttpResponseStatusCodeException, lbResponse)));
				throw clientHttpResponseStatusCodeException;
			}
			supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onComplete(
					new CompletionContext<ClientHttpResponse, ServiceInstance>(
							CompletionContext.Status.SUCCESS, lbResponse, response)));
			return response;
		}, new LoadBalancedRecoveryCallback<ClientHttpResponse, ClientHttpResponse>() {
			// This is a special case, where both parameters to
			// LoadBalancedRecoveryCallback are
			// the same. In most cases they would be different.
			@Override
			protected ClientHttpResponse createResponse(ClientHttpResponse response,
					URI uri) {
				return response;
			}
		});
	}

	private RetryTemplate createRetryTemplate(String serviceName, HttpRequest request,
			LoadBalancedRetryPolicy retryPolicy) {
		RetryTemplate template = new RetryTemplate();
		BackOffPolicy backOffPolicy = this.lbRetryFactory
				.createBackOffPolicy(serviceName);
		template.setBackOffPolicy(
				backOffPolicy == null ? new NoBackOffPolicy() : backOffPolicy);
		template.setThrowLastExceptionOnExhausted(true);
		RetryListener[] retryListeners = this.lbRetryFactory
				.createRetryListeners(serviceName);
		if (retryListeners != null && retryListeners.length != 0) {
			template.setListeners(retryListeners);
		}
		template.setRetryPolicy(!this.retryProperties.isEnabled() || retryPolicy == null
				? new NeverRetryPolicy() : new InterceptorRetryPolicy(request,
						retryPolicy, this.loadBalancer, serviceName));
		return template;
	}

	private Set<LoadBalancerLifecycle> supportedLifecycleProcessors(String serviceId) {
		return loadBalancerFactory.getInstances(serviceId, LoadBalancerLifecycle.class)
				.values().stream()
				.filter(lifecycle -> lifecycle.supports(HttpRequestContext.class,
						ClientHttpResponse.class, ServiceInstance.class))
				.collect(Collectors.toSet());
	}

}
