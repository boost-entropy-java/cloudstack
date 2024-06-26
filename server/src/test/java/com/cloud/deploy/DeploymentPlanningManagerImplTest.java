// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.deploy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.dc.DataCenter;
import com.cloud.host.Host;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import org.apache.cloudstack.affinity.dao.AffinityGroupDomainMapDao;
import org.apache.commons.collections.CollectionUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import org.apache.cloudstack.affinity.AffinityGroupProcessor;
import org.apache.cloudstack.affinity.AffinityGroupService;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.engine.cloud.entity.api.db.dao.VMReservationDao;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.test.utils.SpringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanner.PlannerResourceUsage;
import com.cloud.deploy.dao.PlannerHostReservationDao;
import com.cloud.exception.AffinityConflictException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.gpu.dao.HostGpuGroupsDao;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.org.Grouping.AllocationState;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.StorageManager;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.AccountManager;
import com.cloud.utils.component.ComponentContext;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.UserVmDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.host.dao.HostDetailsDao;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(loader = AnnotationConfigContextLoader.class)
public class DeploymentPlanningManagerImplTest {

    @Spy
    @InjectMocks
    DeploymentPlanningManagerImpl _dpm;

    @Inject
    PlannerHostReservationDao _plannerHostReserveDao;

    @Inject
    VirtualMachineProfileImpl vmProfile;

    @Inject
    private AccountDao accountDao;

    @Inject
    private VMInstanceDao vmInstanceDao;

    @Inject
    AffinityGroupVMMapDao _affinityGroupVMMapDao;

    @Inject
    ExcludeList avoids;

    @Inject
    DataCenterVO dc;

    @Inject
    DataCenterDao _dcDao;

    @Mock
    FirstFitPlanner _planner;

    @Inject
    ClusterDao _clusterDao;

    @Inject
    DedicatedResourceDao _dedicatedDao;

    @Inject
    UserVmDetailsDao vmDetailsDao;

    @Inject
    VMTemplateDao templateDao;

    @Inject
    HostPodDao hostPodDao;

    @Mock
    Host host;

    private static long dataCenterId = 1L;
    private static long hostId = 1l;
    private static final long ADMIN_ACCOUNT_ROLE_ID = 1l;

    @BeforeClass
    public static void setUp() throws ConfigurationException {
    }

    @Before
    public void testSetUp() {
        MockitoAnnotations.initMocks(this);

        ComponentContext.initComponentsLifeCycle();

        PlannerHostReservationVO reservationVO = new PlannerHostReservationVO(200L, 1L, 2L, 3L, PlannerResourceUsage.Shared);
        Mockito.when(_plannerHostReserveDao.persist(Matchers.any(PlannerHostReservationVO.class))).thenReturn(reservationVO);
        Mockito.when(_plannerHostReserveDao.findById(Matchers.anyLong())).thenReturn(reservationVO);
        Mockito.when(_affinityGroupVMMapDao.countAffinityGroupsForVm(Matchers.anyLong())).thenReturn(0L);

        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.isDeployAsIs()).thenReturn(false);
        Mockito.when(templateDao.findById(Mockito.anyLong())).thenReturn(template);

        VMInstanceVO vm = new VMInstanceVO();
        Mockito.when(vmProfile.getVirtualMachine()).thenReturn(vm);

        Mockito.when(vmDetailsDao.listDetailsKeyPairs(Matchers.anyLong())).thenReturn(null);

        Mockito.when(_dcDao.findById(Matchers.anyLong())).thenReturn(dc);
        Mockito.when(dc.getId()).thenReturn(dataCenterId);

        ClusterVO clusterVO = new ClusterVO();
        clusterVO.setHypervisorType(HypervisorType.XenServer.toString());
        Mockito.when(_clusterDao.findById(Matchers.anyLong())).thenReturn(clusterVO);

        Mockito.when(_planner.getName()).thenReturn("FirstFitPlanner");
        List<DeploymentPlanner> planners = new ArrayList<DeploymentPlanner>();
        planners.add(_planner);
        _dpm.setPlanners(planners);

        Mockito.when(host.getId()).thenReturn(hostId);
        Mockito.doNothing().when(_dpm).avoidDisabledResources(vmProfile, dc, avoids);
    }

    @Test
    public void dataCenterAvoidTest() throws InsufficientServerCapacityException, AffinityConflictException {
        ServiceOfferingVO svcOffering =
            new ServiceOfferingVO("testOffering", 1, 512, 500, 1, 1, false, false, false, "test dpm",
                    false, VirtualMachine.Type.User, null, "FirstFitPlanner", true, false);
        Mockito.when(vmProfile.getServiceOffering()).thenReturn(svcOffering);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);

        Mockito.when(avoids.shouldAvoid((DataCenterVO)Matchers.anyObject())).thenReturn(true);
        DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);
        assertNull("DataCenter is in avoid set, destination should be null! ", dest);
    }

    @Test
    public void plannerCannotHandleTest() throws InsufficientServerCapacityException, AffinityConflictException {
        ServiceOfferingVO svcOffering =
            new ServiceOfferingVO("testOffering", 1, 512, 500, 1, 1, false, false, false, "test dpm",
                    false, VirtualMachine.Type.User, null, "UserDispersingPlanner", true, false);
        Mockito.when(vmProfile.getServiceOffering()).thenReturn(svcOffering);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);
        Mockito.when(avoids.shouldAvoid((DataCenterVO)Matchers.anyObject())).thenReturn(false);

        Mockito.when(_planner.canHandle(vmProfile, plan, avoids)).thenReturn(false);
        DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);
        assertNull("Planner cannot handle, destination should be null! ", dest);
    }

    @Test
    public void emptyClusterListTest() throws InsufficientServerCapacityException, AffinityConflictException {
        ServiceOfferingVO svcOffering =
            new ServiceOfferingVO("testOffering", 1, 512, 500, 1, 1, false, false, false, "test dpm",
                    false, VirtualMachine.Type.User, null, "FirstFitPlanner", true, false);
        Mockito.when(vmProfile.getServiceOffering()).thenReturn(svcOffering);

        DataCenterDeployment plan = new DataCenterDeployment(dataCenterId);
        Mockito.when(avoids.shouldAvoid((DataCenterVO)Matchers.anyObject())).thenReturn(false);
        Mockito.when(_planner.canHandle(vmProfile, plan, avoids)).thenReturn(true);

        Mockito.when(((DeploymentClusterPlanner)_planner).orderClusters(vmProfile, plan, avoids)).thenReturn(null);
        DeployDestination dest = _dpm.planDeployment(vmProfile, plan, avoids, null);
        assertNull("Planner cannot handle, destination should be null! ", dest);
    }

    @Test
    public void testCheckAffinityEmptyPreferredHosts() {
        assertTrue(_dpm.checkAffinity(host, new ArrayList<>()));
    }

    @Test
    public void testCheckAffinityNullPreferredHosts() {
        assertTrue(_dpm.checkAffinity(host, null));
    }

    @Test
    public void testCheckAffinityNotEmptyPreferredHostsContainingHost() {
        assertTrue(_dpm.checkAffinity(host, Arrays.asList(3l, 4l, hostId, 2l)));
    }

    @Test
    public void testCheckAffinityNotEmptyPreferredHostsNotContainingHost() {
        assertFalse(_dpm.checkAffinity(host, Arrays.asList(3l, 4l, 2l)));
    }

    @Test
    public void routerInDisabledResourceAssertFalse() {
        Assert.assertFalse(DeploymentPlanningManager.allowRouterOnDisabledResource.value());
    }

    @Test
    public void adminVmInDisabledResourceAssertFalse() {
        Assert.assertFalse(DeploymentPlanningManager.allowAdminVmOnDisabledResource.value());
    }

    @Test
    public void avoidDisabledResourcesTestAdminAccount() {
        Type[] vmTypes = VirtualMachine.Type.values();
        for (int i = 0; i < vmTypes.length - 1; ++i) {
            Mockito.when(vmProfile.getType()).thenReturn(vmTypes[i]);
            if (vmTypes[i].isUsedBySystem()) {
                prepareAndVerifyAvoidDisabledResourcesTest(1, 0, 0, ADMIN_ACCOUNT_ROLE_ID, vmTypes[i], true, false);
            } else {
                prepareAndVerifyAvoidDisabledResourcesTest(0, 1, 1, ADMIN_ACCOUNT_ROLE_ID, vmTypes[i], true, false);
            }
        }
    }

    @Test
    public void avoidDisabledResourcesTestUserAccounAdminCannotDeployOnDisabled() {
        Type[] vmTypes = VirtualMachine.Type.values();
        for (int i = 0; i < vmTypes.length - 1; ++i) {
            Mockito.when(vmProfile.getType()).thenReturn(vmTypes[i]);
            long userAccountId = ADMIN_ACCOUNT_ROLE_ID + 1;
            if (vmTypes[i].isUsedBySystem()) {
                prepareAndVerifyAvoidDisabledResourcesTest(1, 0, 0, userAccountId, vmTypes[i], true, false);
            } else {
                prepareAndVerifyAvoidDisabledResourcesTest(0, 0, 1, userAccountId, vmTypes[i], true, false);
            }
        }
    }

    @Test
    public void avoidDisabledResourcesTestUserAccounAdminCanDeployOnDisabled() {
        Type[] vmTypes = VirtualMachine.Type.values();
        for (int i = 0; i < vmTypes.length - 1; ++i) {
            Mockito.when(vmProfile.getType()).thenReturn(vmTypes[i]);
            long userAccountId = ADMIN_ACCOUNT_ROLE_ID + 1;
            if (vmTypes[i].isUsedBySystem()) {
                prepareAndVerifyAvoidDisabledResourcesTest(1, 0, 0, userAccountId, vmTypes[i], true, true);
            } else {
                prepareAndVerifyAvoidDisabledResourcesTest(0, 0, 1, userAccountId, vmTypes[i], true, true);
            }
        }
    }

    private void prepareAndVerifyAvoidDisabledResourcesTest(int timesRouter, int timesAdminVm, int timesDisabledResource, long roleId, Type vmType, boolean isSystemDepolyable,
            boolean isAdminVmDeployable) {
        Mockito.doReturn(isSystemDepolyable).when(_dpm).isRouterDeployableInDisabledResources();
        Mockito.doReturn(isAdminVmDeployable).when(_dpm).isAdminVmDeployableInDisabledResources();

        VirtualMachineProfile vmProfile = Mockito.mock(VirtualMachineProfile.class);
        DataCenter dc = Mockito.mock(DataCenter.class);
        ExcludeList avoids = Mockito.mock(ExcludeList.class);

        Mockito.when(vmProfile.getType()).thenReturn(vmType);
        Mockito.when(vmProfile.getId()).thenReturn(1l);

        Mockito.doNothing().when(_dpm).avoidDisabledDataCenters(dc, avoids);
        Mockito.doNothing().when(_dpm).avoidDisabledPods(dc, avoids);
        Mockito.doNothing().when(_dpm).avoidDisabledClusters(dc, avoids);
        Mockito.doNothing().when(_dpm).avoidDisabledHosts(dc, avoids);

        VMInstanceVO vmInstanceVO = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vmInstanceDao.findById(Mockito.anyLong())).thenReturn(vmInstanceVO);
        AccountVO owner = Mockito.mock(AccountVO.class);
        Mockito.when(owner.getRoleId()).thenReturn(roleId);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(owner);

        _dpm.avoidDisabledResources(vmProfile, dc, avoids);

        Mockito.verify(_dpm, Mockito.times(timesRouter)).isRouterDeployableInDisabledResources();
        Mockito.verify(_dpm, Mockito.times(timesAdminVm)).isAdminVmDeployableInDisabledResources();
        Mockito.verify(_dpm, Mockito.times(timesDisabledResource)).avoidDisabledDataCenters(dc, avoids);
        Mockito.verify(_dpm, Mockito.times(timesDisabledResource)).avoidDisabledPods(dc, avoids);
        Mockito.verify(_dpm, Mockito.times(timesDisabledResource)).avoidDisabledClusters(dc, avoids);
        Mockito.verify(_dpm, Mockito.times(timesDisabledResource)).avoidDisabledHosts(dc, avoids);
        Mockito.reset(_dpm);
    }

    @Test
    public void avoidDisabledDataCentersTest() {
        DataCenter dc = Mockito.mock(DataCenter.class);
        Mockito.when(dc.getId()).thenReturn(123l);

        ExcludeList avoids = new ExcludeList();
        AllocationState[] allocationStates = AllocationState.values();
        for (int i = 0; i < allocationStates.length - 1; ++i) {
            Mockito.when(dc.getAllocationState()).thenReturn(allocationStates[i]);

            _dpm.avoidDisabledDataCenters(dc, avoids);

            if (allocationStates[i] == AllocationState.Disabled) {
                assertAvoidIsEmpty(avoids, false, true, true, true);
                Assert.assertTrue(avoids.getDataCentersToAvoid().size() == 1);
                Assert.assertTrue(avoids.getDataCentersToAvoid().contains(dc.getId()));
            } else {
                assertAvoidIsEmpty(avoids, true, true, true, true);
            }
        }
    }

    @Test
    public void avoidDisabledPodsTestNoDisabledPod() {
        DataCenter dc = Mockito.mock(DataCenter.class);
        List<Long> podIds = new ArrayList<>();
        long expectedPodId = 123l;
        podIds.add(expectedPodId);
        Mockito.doReturn(new ArrayList<>()).when(hostPodDao).listDisabledPods(Mockito.anyLong());
        ExcludeList avoids = new ExcludeList();

        _dpm.avoidDisabledPods(dc, avoids);
        assertAvoidIsEmpty(avoids, true, true, true, true);
    }

    @Test
    public void avoidDisabledPodsTestHasDisabledPod() {
        DataCenter dc = Mockito.mock(DataCenter.class);
        List<Long> podIds = new ArrayList<>();
        long expectedPodId = 123l;
        podIds.add(expectedPodId);
        Mockito.doReturn(podIds).when(hostPodDao).listDisabledPods(Mockito.anyLong());

        ExcludeList avoids = new ExcludeList();

        _dpm.avoidDisabledPods(dc, avoids);
        assertAvoidIsEmpty(avoids, true, false, true, true);
        Assert.assertTrue(avoids.getPodsToAvoid().size() == 1);
        Assert.assertTrue(avoids.getPodsToAvoid().contains(expectedPodId));
    }

    @Test
    public void avoidDisabledClustersTestNoDisabledCluster() {
        DataCenter dc = prepareAvoidDisabledTests();
        Mockito.doReturn(new ArrayList<>()).when(_clusterDao).listDisabledClusters(Mockito.anyLong(), Mockito.anyLong());
        ExcludeList avoids = new ExcludeList();

        _dpm.avoidDisabledClusters(dc, avoids);
        assertAvoidIsEmpty(avoids, true, true, true, true);
    }

    @Test
    public void avoidDisabledClustersTestHasDisabledCluster() {
        DataCenter dc = prepareAvoidDisabledTests();
        long expectedClusterId = 123l;
        List<Long> disabledClusters = new ArrayList<>();
        disabledClusters.add(expectedClusterId);
        Mockito.doReturn(disabledClusters).when(_clusterDao).listDisabledClusters(Mockito.anyLong(), Mockito.anyLong());
        ExcludeList avoids = new ExcludeList();

        _dpm.avoidDisabledClusters(dc, avoids);

        assertAvoidIsEmpty(avoids, true, true, false, true);
        Assert.assertTrue(avoids.getClustersToAvoid().size() == 1);
        Assert.assertTrue(avoids.getClustersToAvoid().contains(expectedClusterId));
    }

    private DataCenter prepareAvoidDisabledTests() {
        DataCenter dc = Mockito.mock(DataCenter.class);
        Mockito.when(dc.getId()).thenReturn(123l);
        List<Long> podIds = new ArrayList<>();
        podIds.add(1l);
        Mockito.doReturn(podIds).when(hostPodDao).listAllPods(Mockito.anyLong());
        return dc;
    }

    private void assertAvoidIsEmpty(ExcludeList avoids, boolean isDcEmpty, boolean isPodsEmpty, boolean isClustersEmpty, boolean isHostsEmpty) {
        Assert.assertEquals(isDcEmpty, CollectionUtils.isEmpty(avoids.getDataCentersToAvoid()));
        Assert.assertEquals(isPodsEmpty, CollectionUtils.isEmpty(avoids.getPodsToAvoid()));
        Assert.assertEquals(isClustersEmpty, CollectionUtils.isEmpty(avoids.getClustersToAvoid()));
        Assert.assertEquals(isHostsEmpty, CollectionUtils.isEmpty(avoids.getHostsToAvoid()));
    }

    @Configuration
    @ComponentScan(basePackageClasses = {DeploymentPlanningManagerImpl.class}, includeFilters = {@Filter(value = TestConfiguration.Library.class,
                                                                                                         type = FilterType.CUSTOM)}, useDefaultFilters = false)
    public static class TestConfiguration extends SpringUtils.CloudStackTestConfiguration {

        @Bean
        public FirstFitPlanner firstFitPlanner() {
            return Mockito.mock(FirstFitPlanner.class);
        }

        @Bean
        public DeploymentPlanner deploymentPlanner() {
            return Mockito.mock(DeploymentPlanner.class);
        }

        @Bean
        public DataCenterVO dataCenter() {
            return Mockito.mock(DataCenterVO.class);
        }

        @Bean
        public ExcludeList excludeList() {
            return Mockito.mock(ExcludeList.class);
        }

        @Bean
        public VirtualMachineProfileImpl virtualMachineProfileImpl() {
            return Mockito.mock(VirtualMachineProfileImpl.class);
        }

        @Bean
        public HostTagsDao hostTagsDao() {
            return Mockito.mock(HostTagsDao.class);
        }

        @Bean
        public HostDetailsDao hostDetailsDao() {
            return Mockito.mock(HostDetailsDao.class);
        }


        @Bean
        public ClusterDetailsDao clusterDetailsDao() {
            return Mockito.mock(ClusterDetailsDao.class);
        }

        @Bean
        public ResourceManager resourceManager() {
            return Mockito.mock(ResourceManager.class);
        }

        @Bean
        public ServiceOfferingDetailsDao serviceOfferingDetailsDao() {
            return Mockito.mock(ServiceOfferingDetailsDao.class);
        }

        @Bean
        public AffinityGroupDomainMapDao affinityGroupDomainMapDao() {
            return Mockito.mock(AffinityGroupDomainMapDao.class);
        }

        @Bean
        public DataStoreManager cataStoreManager() {
            return Mockito.mock(DataStoreManager.class);
        }

        @Bean
        public StorageManager storageManager() {
            return Mockito.mock(StorageManager.class);
        }

        @Bean
        public HostDao hostDao() {
            return Mockito.mock(HostDao.class);
        }

        @Bean
        public HostPodDao hostPodDao() {
            return Mockito.mock(HostPodDao.class);
        }

        @Bean
        public ClusterDao clusterDao() {
            return Mockito.mock(ClusterDao.class);
        }

        @Bean
        public DedicatedResourceDao dedicatedResourceDao() {
            return Mockito.mock(DedicatedResourceDao.class);
        }

        @Bean
        public GuestOSDao guestOSDao() {
            return Mockito.mock(GuestOSDao.class);
        }

        @Bean
        public GuestOSCategoryDao guestOSCategoryDao() {
            return Mockito.mock(GuestOSCategoryDao.class);
        }

        @Bean
        public CapacityManager capacityManager() {
            return Mockito.mock(CapacityManager.class);
        }

        @Bean
        public StoragePoolHostDao storagePoolHostDao() {
            return Mockito.mock(StoragePoolHostDao.class);
        }

        @Bean
        public VolumeDao volumeDao() {
            return Mockito.mock(VolumeDao.class);
        }

        @Bean
        public ConfigurationDao configurationDao() {
            return Mockito.mock(ConfigurationDao.class);
        }

        @Bean
        public DiskOfferingDao diskOfferingDao() {
            return Mockito.mock(DiskOfferingDao.class);
        }

        @Bean
        public PrimaryDataStoreDao primaryDataStoreDao() {
            return Mockito.mock(PrimaryDataStoreDao.class);
        }

        @Bean
        public CapacityDao capacityDao() {
            return Mockito.mock(CapacityDao.class);
        }

        @Bean
        public PlannerHostReservationDao plannerHostReservationDao() {
            return Mockito.mock(PlannerHostReservationDao.class);
        }

        @Bean
        public AffinityGroupProcessor affinityGroupProcessor() {
            return Mockito.mock(AffinityGroupProcessor.class);
        }

        @Bean
        public AffinityGroupDao affinityGroupDao() {
            return Mockito.mock(AffinityGroupDao.class);
        }

        @Bean
        public AffinityGroupVMMapDao affinityGroupVMMapDao() {
            return Mockito.mock(AffinityGroupVMMapDao.class);
        }

        @Bean
        public AccountManager accountManager() {
            return Mockito.mock(AccountManager.class);
        }

        @Bean
        public AgentManager agentManager() {
            return Mockito.mock(AgentManager.class);
        }

        @Bean
        public MessageBus messageBus() {
            return Mockito.mock(MessageBus.class);
        }

        @Bean
        public UserVmDao userVMDao() {
            return Mockito.mock(UserVmDao.class);
        }

        @Bean
        public UserVmDetailsDao userVmDetailsDao() {
            return Mockito.mock(UserVmDetailsDao.class);
        }

        @Bean
        public VMInstanceDao vmInstanceDao() {
            return Mockito.mock(VMInstanceDao.class);
        }

        @Bean
        public DataCenterDao dataCenterDao() {
            return Mockito.mock(DataCenterDao.class);
        }

        @Bean
        public VMReservationDao reservationDao() {
            return Mockito.mock(VMReservationDao.class);
        }

        @Bean
        public AffinityGroupService affinityGroupService() {
            return Mockito.mock(AffinityGroupService.class);
        }

        @Bean
        public HostGpuGroupsDao hostGpuGroupsDao() {
            return Mockito.mock(HostGpuGroupsDao.class);
        }

        @Bean
        public AccountDao accountDao() {
            return Mockito.mock(AccountDao.class);
        }

        @Bean
        public VMTemplateDao vmTemplateDao() {
            return Mockito.mock(VMTemplateDao.class);
        }

        public static class Library implements TypeFilter {

            @Override
            public boolean match(MetadataReader mdr, MetadataReaderFactory arg1) throws IOException {
                ComponentScan cs = TestConfiguration.class.getAnnotation(ComponentScan.class);
                return SpringUtils.includedInBasePackageClasses(mdr.getClassMetadata().getClassName(), cs);
            }
        }
    }
}
