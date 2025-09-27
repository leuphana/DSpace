/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.content;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.solr.client.solrj.util.ClientUtils;
import org.dspace.content.duplicatedetection.DuplicateComparison;
import org.dspace.content.duplicatedetection.DuplicateComparisonValueTransformer;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.factory.ContentServiceFactoryImpl;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.discovery.DiscoverQuery;
import org.dspace.discovery.DiscoverResult;
import org.dspace.discovery.SearchService;
import org.dspace.discovery.SearchServiceException;
import org.dspace.services.ConfigurationService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DuplicateDetectionServiceImplTest {

    @Mock
    ConfigurationService configurationService;
    @Mock
    ItemService itemService;
    @Mock
    SearchService searchService;
    @Mock
    Context context;
    @Mock
    Item item;

    @Spy
    @InjectMocks
    ContentServiceFactoryImpl contentServiceFactory;

    @Spy
    @InjectMocks
    DuplicateDetectionServiceImpl duplicateDetectionService;

    @Before
    public void setUp() {
        when(context.getCurrentLocale()).thenReturn(Locale.GERMAN);
    }

    /**
     * Tests the `searchDuplicates` method of the `DuplicateDetectionService` to ensure it functions as expected.
     * Specifically, this test verifies the construction of Solr query parameters based on the configuration
     * of the fields considered for duplicate detection.
     *
     * @throws SearchServiceException if the search operation encounters an exception
     */
    @Test
    public void testSearchDuplicates() throws SearchServiceException {
        String value1 = "thisisawonderfultitle";
        String value2 = "isvariantformofxxxxxx/13";
        String value3 = "isvariantformofxxxxxx/14";
        when(configurationService.getProperty("duplicate.comparison.query.operator", "AND"))
            .thenReturn("AND");
        when(configurationService.getProperty("duplicate.comparison.solr.field.prefix", "deduplication"))
            .thenReturn("deduplication");
        when(configurationService.getProperty("duplicate.comparison.solr.field.suffix", "keyword"))
            .thenReturn("keyword");
        DuplicateComparison comparisonValue1 = new DuplicateComparison("dc.title", value1, 1);
        DuplicateComparison comparisonValue2 = new DuplicateComparison("local.custom", value2, 0);
        DuplicateComparison comparisonValue3 = new DuplicateComparison("local.custom", value3, 0);
        DiscoverResult discoverResult = mock(DiscoverResult.class);
        doReturn(List.of(comparisonValue1, comparisonValue2, comparisonValue3)).when(duplicateDetectionService)
            .buildComparisonValue(context, item);
        when(searchService.escapeQueryChars(any())).thenAnswer(
            invocation -> ClientUtils.escapeQueryChars(invocation.getArgument(0)));
        when(searchService.search(any(), any())).thenAnswer(invocation -> {
            DiscoverQuery discoverQuery = invocation.getArgument(1);

            assertEquals("((deduplication_dc_title_keyword:" + value1 + "~1) AND " +
                "(deduplication_local_custom_keyword:" + value2.replace("/", "\\/") + "~0 OR " +
                "deduplication_local_custom_keyword:" + value3.replace("/", "\\/") + "~0))",
                discoverQuery.getQuery());
            return discoverResult;
        });

        assertEquals(discoverResult, duplicateDetectionService.searchDuplicates(context, item));
    }

    /**
     * Unit test for the `buildComparisonValue` method of the `DuplicateDetectionService`.
     *
     * Validates the proper construction of duplicate comparison values using metadata fields
     * defined in the configuration. The test ensures that:
     * - Metadata values are transformed and normalized based on specified rules.
     * - Custom transformations for specific fields are applied as configured.
     * - The resulting comparison values adhere to the transformation and normalization logic.
     *
     * The test uses mock objects for dependencies like `configurationService`, `itemService`,
     * and `metadataValue` to simulate behavior and assert the expected results of the method.
     *
     * Assertions include:
     * - Ensuring the correct number of comparison values are generated.
     * - Validating the content of the generated comparison values using normalization and custom transformations.
     *
     * A mocked static context for `ContentServiceFactory.getInstance()` is used to avoid integration with
     * the actual factory implementation.
     */
    @Test
    public void testBuildComparisonValues() {
        try (MockedStatic<ContentServiceFactory> contentServiceFactoryMockedStatic = mockStatic(
            ContentServiceFactory.class)) {
            contentServiceFactoryMockedStatic.when(ContentServiceFactory::getInstance)
                .thenReturn(contentServiceFactory);
            String titleField = "dc.title";
            String titleValue = "This is a Wonderful title";
            int titleFieldDistance = 1;
            String customField = "local.custom";
            String customFieldValue = "isVariantFormOf DOI xxxxxx/13; some other stuff";
            MetadataValue titleMetadataValue = mock(MetadataValue.class);
            when(titleMetadataValue.getValue()).thenReturn(titleValue);
            MetadataValue customMetadataValue = mock(MetadataValue.class);
            when(customMetadataValue.getValue()).thenReturn(customFieldValue);
            when(configurationService.getArrayProperty("duplicate.comparison.metadata.field",
                new String[] {"dc.title"})).thenReturn(
                new String[] {titleField + ":" + titleFieldDistance, customField});
            when(configurationService.getIntProperty("duplicate.comparison.distance", 0))
                .thenReturn(0);
            when(configurationService.getBooleanProperty("duplicate.comparison.normalise.lowercase"))
                .thenReturn(true);
            when(configurationService.getBooleanProperty("duplicate.comparison.normalise.whitespace"))
                .thenReturn(true);
            when(itemService.getMetadata(item, "dc", "title", null, Item.ANY))
                .thenReturn(List.of(titleMetadataValue));
            when(itemService.getMetadata(item, "local", "custom", null, Item.ANY))
                .thenReturn(List.of(customMetadataValue));

            Map<String, DuplicateComparisonValueTransformer> duplicateComparisonValueTransformerMap = new HashMap<>();
            duplicateComparisonValueTransformerMap.put("local.custom", new DuplicateComparisonValueTransformer() {
                @Override
                public String transform(String value) {
                    String[] parts = value.split(";")[0].split(" ");
                    return parts[0] + parts[2];
                }
            });
            duplicateDetectionService.setDuplicateComparisonValueTransformers(duplicateComparisonValueTransformerMap);
            duplicateDetectionService.init();
            List<DuplicateComparison> duplicateComparisons =
                duplicateDetectionService.buildComparisonValue(context, item);

            assertEquals(2, duplicateComparisons.size());
            assertEquals("thisisawonderfultitle", duplicateComparisons.get(0).value());
            assertEquals("isvariantformofxxxxxx/13", duplicateComparisons.get(1).value());
        }
    }
}