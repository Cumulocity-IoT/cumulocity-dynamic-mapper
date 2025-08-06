// /*
//  * Copyright (c) 2022-2025 Cumulocity GmbH.
//  *
//  * SPDX-License-Identifier: Apache-2.0
//  *
//  *  Licensed under the Apache License, Version 2.0 (the "License");
//  *  you may not use this file except in compliance with the License.
//  *  You may obtain a copy of the License at
//  *
//  *       http://www.apache.org/licenses/LICENSE-2.0
//  *
//  *  Unless required by applicable law or agreed to in writing, software
//  *  distributed under the License is distributed on an "AS IS" BASIS,
//  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  *  See the License for the specific language governing permissions and
//  *  limitations under the License.
//  *
//  *  @authors Christof Strack, Stefan Witschel
//  *
//  */

// package dynamic.mapper.core;

// import org.springframework.beans.factory.annotation.Qualifier;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.context.annotation.Primary;

// import com.cumulocity.sdk.client.Platform;
// import com.cumulocity.sdk.client.PlatformImpl;
// import com.cumulocity.sdk.client.interceptor.HttpClientInterceptor;
// import com.cumulocity.sdk.client.measurement.MeasurementApi;

// import jakarta.ws.rs.client.Invocation;

// @Configuration
// public class C8YClientConfiguration {

//     @Bean
//     @Primary
//     public Platform platform() {
//         // Your existing platform configuration
//         return createPlatform();
//     }

//     @Bean("platformTransient") 
//     public Platform platformTransient() {
//         Platform platform = createPlatform();
        
//         // Try to configure the transient platform if possible
//         if (platform instanceof PlatformImpl) {
//             ((PlatformImpl) platform).registerInterceptor(new HttpClientInterceptor() {
//                 @Override
//                 public Invocation.Builder apply(Invocation.Builder builder) {
//                     return builder.header("X-Cumulocity-Processing-Mode", "TRANSIENT");
//                 }
//             });
//         }
        
//         return platform;
//     }
    
    
//     @Bean("measurementApiTransient")
//     public MeasurementApi measurementApiTransient(@Qualifier("platformTransient") Platform platform) {
//         return platform.getMeasurementApi();
//     }

//     private Platform createPlatform() {
//         // Your platform creation logic here
//         // This depends on how your existing platform bean is configured
//         return new PlatformImpl(/* your configuration */);
//     }
// }
