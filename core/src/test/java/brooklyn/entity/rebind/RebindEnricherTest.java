package brooklyn.entity.rebind;

import static org.testng.Assert.assertEquals;

import java.util.Map;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.config.ConfigKey;
import brooklyn.enricher.Enrichers;
import brooklyn.enricher.basic.AbstractEnricher;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.location.LocationSpec;
import brooklyn.location.basic.SimulatedLocation;
import brooklyn.policy.EnricherSpec;
import brooklyn.test.EntityTestUtils;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestEntity;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.text.StringFunctions;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

public class RebindEnricherTest extends RebindTestFixtureWithApp {

    public static AttributeSensor<String> METRIC1 = Sensors.newStringSensor("RebindEnricherTest.metric1");
    public static AttributeSensor<String> METRIC2 = Sensors.newStringSensor("RebindEnricherTest.metric2");
    
    private DynamicCluster origCluster;
    private TestEntity origEntity;
    private SimulatedLocation origLoc;

    @Override
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        super.setUp();
        origCluster = origApp.createAndManageChild(EntitySpec.create(DynamicCluster.class).configure("memberSpec", EntitySpec.create(TestEntity.class)));
        origEntity = origApp.createAndManageChild(EntitySpec.create(TestEntity.class));
        origLoc = origManagementContext.getLocationManager().createLocation(LocationSpec.create(SimulatedLocation.class));
    }
    
    @Test
    public void testPropagatingEnricher() throws Exception {
        origApp.addEnricher(Enrichers.builder()
                .propagating(METRIC1)
                .from(origEntity)
                .build());
        
        TestApplication newApp = rebind();
        TestEntity newEntity = (TestEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(TestEntity.class));

        newEntity.setAttribute(METRIC1, "myval");
        EntityTestUtils.assertAttributeEqualsEventually(newApp, METRIC1, "myval");
    }

    @Test
    public void testPropagatingAllEnricher() throws Exception {
        origApp.addEnricher(Enrichers.builder()
                .propagatingAll()
                .from(origEntity)
                .build());
        
        TestApplication newApp = rebind();
        TestEntity newEntity = (TestEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(TestEntity.class));

        newEntity.setAttribute(METRIC1, "myval");
        EntityTestUtils.assertAttributeEqualsEventually(newApp, METRIC1, "myval");
    }

    @Test
    public void testPropagatingAsEnricher() throws Exception {
        origApp.addEnricher(Enrichers.builder()
                .propagating(ImmutableMap.of(METRIC1, METRIC2))
                .from(origEntity)
                .build());
        
        TestApplication newApp = rebind();
        TestEntity newEntity = (TestEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(TestEntity.class));

        newEntity.setAttribute(METRIC1, "myval");
        EntityTestUtils.assertAttributeEqualsEventually(newApp, METRIC2, "myval");
    }

    @Test
    public void testCombiningEnricher() throws Exception {
        origApp.addEnricher(Enrichers.builder()
                .combining(METRIC1, METRIC2)
                .from(origEntity)
                .computing(StringFunctions.joiner(","))
                .publishing(METRIC2)
                .build());
        
        TestApplication newApp = rebind();
        TestEntity newEntity = (TestEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(TestEntity.class));

        newEntity.setAttribute(METRIC1, "myval");
        newEntity.setAttribute(METRIC2, "myval2");
        EntityTestUtils.assertAttributeEventually(newApp, METRIC2, Predicates.or(Predicates.equalTo("myval,myval2"), Predicates.equalTo("myval2,myval")));
    }

    @Test
    public void testAggregatingMembersEnricher() throws Exception {
        origApp.start(ImmutableList.of(origLoc));
        origCluster.resize(2);
        
        origApp.addEnricher(Enrichers.builder()
                .aggregating(METRIC1)
                .from(origCluster)
                .fromMembers()
                .computing(StringFunctions.joiner(","))
                .publishing(METRIC2)
                .build());
        
        TestApplication newApp = rebind();
        DynamicCluster newCluster = (DynamicCluster) Iterables.find(newApp.getChildren(), Predicates.instanceOf(DynamicCluster.class));

        int i = 1;
        for (Entity member : newCluster.getMembers()) {
            ((EntityInternal)member).setAttribute(METRIC1, "myval"+(i++));
        }
        EntityTestUtils.assertAttributeEventually(newApp, METRIC2, Predicates.or(Predicates.equalTo("myval1,myval2"), Predicates.equalTo("myval2,myval1")));
    }
    
    @Test
    public void testRestoresConfig() throws Exception {
        origApp.addEnricher(EnricherSpec.create(MyEnricher.class)
                .configure(MyEnricher.MY_CONFIG_WITH_SETFROMFLAG_NO_SHORT_NAME, "myVal for with setFromFlag noShortName")
                .configure(MyEnricher.MY_CONFIG_WITH_SETFROMFLAG_WITH_SHORT_NAME, "myVal for setFromFlag withShortName")
                .configure(MyEnricher.MY_CONFIG_WITHOUT_SETFROMFLAG, "myVal for witout setFromFlag"));

        newApp = (TestApplication) rebind();
        MyEnricher newEnricher = (MyEnricher) Iterables.getOnlyElement(newApp.getEnrichers());
        
        assertEquals(newEnricher.getConfig(MyEnricher.MY_CONFIG_WITH_SETFROMFLAG_NO_SHORT_NAME), "myVal for with setFromFlag noShortName");
        assertEquals(newEnricher.getConfig(MyEnricher.MY_CONFIG_WITH_SETFROMFLAG_WITH_SHORT_NAME), "myVal for setFromFlag withShortName");
        assertEquals(newEnricher.getConfig(MyEnricher.MY_CONFIG_WITHOUT_SETFROMFLAG), "myVal for witout setFromFlag");
    }

    public static class MyEnricher extends AbstractEnricher {
        public static final ConfigKey<String> MY_CONFIG = ConfigKeys.newStringConfigKey("myconfigkey");
        
        @SetFromFlag
        public static final ConfigKey<String> MY_CONFIG_WITH_SETFROMFLAG_NO_SHORT_NAME = ConfigKeys.newStringConfigKey("myconfig.withSetfromflag.noShortName");

        @SetFromFlag("myConfigWithSetFromFlagWithShortName")
        public static final ConfigKey<String> MY_CONFIG_WITH_SETFROMFLAG_WITH_SHORT_NAME = ConfigKeys.newStringConfigKey("myconfig.withSetfromflag.withShortName");

        public static final ConfigKey<String> MY_CONFIG_WITHOUT_SETFROMFLAG = ConfigKeys.newStringConfigKey("myconfig.withoutSetfromflag");

        @SetFromFlag
        String myfield;

        @SuppressWarnings("unused")
        private final Object dummy = new Object(); // so not serializable
        
        public volatile boolean initCalled;
        public volatile boolean rebindCalled;
        
        public MyEnricher() {
        }
        
        public MyEnricher(Map<?,?> flags) {
            super(flags);
        }
        
        @Override
        public void init() {
            super.init();
            initCalled = true;
        }
        
        // TODO When AbstractEnricher declares rebind; @Override
        public void rebind() {
            // TODO super.rebind();
            rebindCalled = true;
        }
    }
    
    public static class MyEnricherWithoutNoArgConstructor extends MyEnricher {
        public MyEnricherWithoutNoArgConstructor(Map<?,?> flags) {
            super(flags);
        }
    }
}
