package com.panda.finance

import android.app.*
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.panda.finance.data.*
import com.panda.finance.viewmodel.FinanceViewModel
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.runtime.staticCompositionLocalOf

private fun money(v: Long) = NumberFormat.getNumberInstance(Locale("ar", "EG")).format(v) + " ج"
private fun date(ts: Long) = SimpleDateFormat("dd/MM/yyyy - HH:mm", Locale("ar")).format(Date(ts))
private fun due(ts: Long?) = ts?.let { SimpleDateFormat("dd/MM/yyyy", Locale("ar")).format(Date(it)) } ?: "بدون موعد"
private const val NOTIFICATION_CHANNEL = "finance_due"

private val LocalAppLanguage = staticCompositionLocalOf { "ar" }
private fun tr(ar: String, en: String): String = if (LocalAppLanguage.current == "en") en else ar

private fun showDueNotifications(activity: Activity, accounts: List<Account>) {
    val channel = NotificationChannel(NOTIFICATION_CHANNEL, "مواعيد الحسابات", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "تنبيهات الالتزامات والمستحقات القريبة"
    }
    activity.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    if (android.os.Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    accounts.filter { it.remaining > 0 && it.dueDate != null && it.dueDate!! in now..(now + 3 * day) }.take(3).forEachIndexed { i, a ->
        val title = if (a.type == AccountType.OBLIGATION) "موعد التزام قريب" else "موعد مستحق قريب"
        val text = if (a.type == AccountType.OBLIGATION) "${a.name}: ${money(a.remaining)} مستحق خلال 3 أيام" else "${a.name}: ${money(a.remaining)} متوقع استلامه خلال 3 أيام"
        NotificationManagerCompat.from(activity).notify(1000 + i, NotificationCompat.Builder(activity, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        setContent { PandaFinanceApp() }
    }
}

@Composable fun PandaFinanceApp(vm: FinanceViewModel = viewModel()) {
    val reserve by vm.reservePercent.collectAsState()
    val darkMode by vm.darkMode.collectAsState()
    val appLock by vm.appLock.collectAsState()
    val biometricEnabled by vm.biometricEnabled.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as? FragmentActivity
    val biometricAvailable = remember(activity) { activity?.let { BiometricManager.from(it).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS } == true }
    var unlocked by rememberSaveable { mutableStateOf(!appLock || !vm.hasPin()) }
    LaunchedEffect(appLock) { if (!appLock || !vm.hasPin()) unlocked = true }
    val language by vm.language.collectAsState()
    if (!unlocked) {
        CompositionLocalProvider(LocalAppLanguage provides language) {
            MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
                PinLockScreen(language, biometricEnabled && biometricAvailable, onBiometric = {
                    activity?.let { host ->
                        val prompt = BiometricPrompt(host, host.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
                        })
                        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Panda Finance").setSubtitle(if (language == "ar") "فتح التطبيق بالبصمة" else "Unlock with biometrics").setNegativeButtonText(if (language == "ar") "استخدام PIN" else "Use PIN").build())
                    }
                }) { pin -> val ok = vm.verifyPin(pin); if (ok) unlocked = true; ok }
            }
        }
        return
    }
    CompositionLocalProvider(LocalAppLanguage provides language) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides if (language == "en") androidx.compose.ui.unit.LayoutDirection.Ltr else androidx.compose.ui.unit.LayoutDirection.Rtl) {
            MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) { Surface(Modifier.fillMaxSize()) { FinanceScreen(vm, reserve, darkMode, language) } }
        }
    }
}

@Composable private fun FinanceScreen(vm: FinanceViewModel, reserve: Int, darkMode: Boolean, language: String) {
    val accounts by vm.accounts.collectAsState(); val txs by vm.transactions.collectAsState(); val balance by vm.balance.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }; var selected by remember { mutableStateOf<Account?>(null) }; var detail by remember { mutableStateOf<Account?>(null) }; var editing by remember { mutableStateOf<Account?>(null) }; var deleting by remember { mutableStateOf<Account?>(null) }
    var addType by remember { mutableStateOf<AccountType?>(null) }; var showIncome by remember { mutableStateOf(false) }; var showSettings by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }; val scope = rememberCoroutineScope(); var backupMessage by remember { mutableStateOf<String?>(null) }; var backupAction by remember { mutableStateOf<String?>(null) }; var importedPayload by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) backupAction = "export:$uri" }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch { val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }; if (json != null) { if (json.startsWith("PANDAFINANCE1:")) importedPayload = json else runCatching { vm.importBackup(json); backupMessage = "تم استعادة البيانات" }.onFailure { backupMessage = "ملف النسخة الاحتياطية غير صالح" } } } }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(accounts) { if (accounts.isNotEmpty() && context is Activity) showDueNotifications(context, accounts) }

    Scaffold(topBar = { TopAppBar(title = { Text("Panda Finance", fontWeight = FontWeight.Bold) }, actions = { IconButton({ showSettings = true }) { Icon(Icons.Default.Settings, "الإعدادات") } }) }, bottomBar = {
        NavigationBar { listOf(tr("الرئيسية","Home") to Icons.Default.Home, tr("عليك","You owe") to Icons.Default.ArrowUpward, tr("لك","Owed to you") to Icons.Default.ArrowDownward, tr("السجل","Transactions") to Icons.Default.List).forEachIndexed { i, item -> NavigationBarItem(tab == i, { tab = i }, icon = { Icon(item.second, null) }, label = { Text(item.first) }) } }
    }, floatingActionButton = { FloatingActionButton({ if (tab == 0 || tab == 3) showIncome = true else addType = if (tab == 1) AccountType.OBLIGATION else AccountType.RECEIVABLE }) { Icon(Icons.Default.Add, tr("إضافة","Add")) } }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when(tab) {
                0 -> { item { BalanceCard(balance) }; item { FinanceSummary(accounts, balance) }; item { ReserveCard(reserve) }; item { QuickBackupCard({ createBackup.launch("panda-finance-backup.pfb") }, { openBackup.launch(arrayOf("application/octet-stream","text/*")) }) }; item { Text(tr("آخر العمليات","Recent transactions"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }; items(txs.take(8), key={it.id}) { TxRow(it) { vm.undo(it) } } }
                1,2 -> { val type = if(tab==1) AccountType.OBLIGATION else AccountType.RECEIVABLE; item { SearchField(search) { search = it } }; val list = accounts.filter { it.type==type && it.remaining>0 && it.name.contains(search, true) }; if(list.isEmpty()) item { EmptyState("لا توجد حسابات مطابقة") }; items(list, key={it.id}) { AccountCard(it,balance,reserve) { detail=it } } }
                3 -> { item { SearchField(search) { search=it } }; val list=txs.filter{it.description.contains(search,true)}; if(list.isEmpty()) item{EmptyState("لا توجد معاملات")}; items(list,key={it.id}){TxRow(it){vm.undo(it)}} }
            }
        }
    }
    if(showIncome) IncomeDialog({showIncome=false}){a,d->vm.addIncome(a,d);showIncome=false}
    addType?.let{type->AccountDialog(type,{addType=null}){n,a,note,dueDate->vm.addAccount(n,type,a,note,dueDate);addType=null}}
    selected?.let{a->SettleDialog(a,vm.suggestedPayment(a,reserve),vm.suggestedPercent(a,reserve),balance,{selected=null}){amount->vm.settle(a,amount);selected=null}}
    detail?.let { a -> AccountDetailsDialog(a, { detail=null; selected=a }, { detail=null; editing=a }, { detail=null; deleting=a }, { detail=null }) }
    editing?.let { a -> EditAccountDialog(a, { editing=null }) { n, amount, note, dueDate -> vm.updateAccount(a,n,amount,note,dueDate); editing=null } }
    deleting?.let { a -> AlertDialog(onDismissRequest={deleting=null}, title={Text(tr("حذف الحساب؟","Delete account?"))}, text={Text(tr("سيتم حذف ${a.name} وسجل معاملاته المرتبط به. لا يمكن التراجع عن الحذف.","${a.name} and its transaction history will be deleted. This cannot be undone."))}, confirmButton={TextButton({vm.deleteAccount(a);deleting=null}){Text(tr("حذف","Delete"),color=MaterialTheme.colorScheme.error)}}, dismissButton={TextButton({deleting=null}){Text(tr("إلغاء","Cancel"))}}) }
    if(showSettings) SettingsDialog(reserve,darkMode,language,appLock,vm.hasPin(),biometricEnabled,biometricAvailable,{showSettings=false},{r,d,l,lock,bio->{vm.setReserve(r);vm.setDarkMode(d);vm.setLanguage(l);vm.setAppLock(lock);vm.setBiometricEnabled(bio && biometricAvailable)}},{createBackup.launch("panda-finance-backup.pfb")},{openBackup.launch(arrayOf("application/octet-stream","text/*"))},{pin->{vm.setPin(pin);vm.setAppLock(true)}})
    backupMessage?.let { msg -> LaunchedEffect(msg) { kotlinx.coroutines.delay(1800); backupMessage=null }; Text(msg, modifier=Modifier.padding(8.dp), color=MaterialTheme.colorScheme.primary) }
    backupAction?.let { action -> if (action.startsWith("export:")) BackupPinDialog({backupAction=null}) { pin -> val uri=Uri.parse(action.removePrefix("export:")); scope.launch { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportEncryptedBackup(pin).toByteArray()) }; backupMessage="تم إنشاء نسخة احتياطية مشفرة" }.onFailure { backupMessage="تعذر إنشاء النسخة الاحتياطية" }; backupAction=null } } }
    importedPayload?.let { payload -> BackupPinDialog({importedPayload=null}) { pin -> scope.launch { runCatching { vm.importBackup(payload,pin); backupMessage="تم استعادة البيانات" }.onFailure { backupMessage="PIN غير صحيح أو الملف تالف" }; importedPayload=null } } }
}

@Composable private fun BackupPinDialog(dismiss:()->Unit, confirm:(String)->Unit){ var pin by remember{mutableStateOf("")}; AlertDialog(onDismissRequest=dismiss,title={Text(tr("رمز النسخة الاحتياطية","Backup PIN"))},text={OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text(tr("أدخل PIN","Enter PIN"))},singleLine=true)},confirmButton={TextButton(enabled=pin.length in 4..8,onClick={confirm(pin)}){Text(tr("متابعة","Continue"))}},dismissButton={TextButton(dismiss){Text(tr("إلغاء","Cancel"))}})}

@Composable private fun PinLockScreen(language:String, biometricAvailable:Boolean, onBiometric:()->Unit, onUnlock:(String)->Boolean){
    var pin by remember{mutableStateOf("")}; var error by remember{mutableStateOf(false)}
    CompositionLocalProvider(LocalAppLanguage provides language){
        Box(Modifier.fillMaxSize().padding(28.dp),contentAlignment=Alignment.Center){
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)){
                Icon(Icons.Default.Lock,null,Modifier.size(56.dp)); Text("Panda Finance",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold); Text(tr("التطبيق مقفول","App locked"));
                OutlinedTextField(pin,{v->pin=v.filter(Char::isDigit).take(8);error=false},label={Text(tr("رمز PIN","PIN"))},singleLine=true);
                if(error) Text(tr("رمز PIN غير صحيح","Incorrect PIN"),color=MaterialTheme.colorScheme.error);
                Button(enabled=pin.length>=4,onClick={ error = !onUnlock(pin) }){Text(tr("فتح التطبيق","Unlock"))}
                if (biometricAvailable) OutlinedButton(onClick=onBiometric){Icon(Icons.Default.Fingerprint,null); Spacer(Modifier.width(8.dp)); Text(tr("فتح بالبصمة","Unlock with biometrics"))}
            }
        }
    }
}

@Composable private fun BalanceCard(balance:Long){ Card(shape=RoundedCornerShape(24.dp)){Column(Modifier.fillMaxWidth().padding(22.dp)){Text(tr("الرصيد المتاح","Available balance"),style=MaterialTheme.typography.titleMedium);Text(money(balance),style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold);Text(if(balance>=0)tr("متاح للاستخدام","Available to spend") else tr("تنبيه: الرصيد سالب","Warning: negative balance"))}}}
@Composable private fun FinanceSummary(accounts:List<Account>,balance:Long){val o=accounts.filter{it.type==AccountType.OBLIGATION&&it.remaining>0}.sumOf{it.remaining};val r=accounts.filter{it.type==AccountType.RECEIVABLE&&it.remaining>0}.sumOf{it.remaining};Card{Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween){SummaryItem(tr("عليك","You owe"),o);SummaryItem(tr("لك","Owed to you"),r);SummaryItem(tr("الصافي","Net"),balance+r-o)}}}
@Composable private fun SummaryItem(t:String,v:Long){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(t);Text(money(v),fontWeight=FontWeight.Bold)}}
@Composable private fun ReserveCard(v:Int){Card{Column(Modifier.padding(16.dp)){Text(tr("احتياطي الرصيد","Balance reserve"),fontWeight=FontWeight.Bold);Text(tr("$v% من الرصيد يظل محميًا عند اقتراح سداد الالتزامات.","$v% of the balance is protected when suggesting obligation payments."))}}}
@Composable private fun QuickBackupCard(onExport:()->Unit,onImport:()->Unit){Card{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(tr("النسخ الاحتياطي","Backup"),fontWeight=FontWeight.Bold);Text(tr("احفظ بياناتك في ملف واستعدها على أي جهاز.","Save your data to a file and restore it on any device."));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onExport,Modifier.weight(1f)){Text(tr("تصدير","Export"))};OutlinedButton(onImport,Modifier.weight(1f)){Text(tr("استيراد","Import"))}}}}}
@Composable private fun SearchField(v:String,onChange:(String)->Unit){OutlinedTextField(v,onChange,label={Text(tr("بحث","Search"))},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,modifier=Modifier.fillMaxWidth())}
@Composable private fun AccountCard(a:Account,balance:Long,reserve:Int,onOpen:()->Unit){val suggested=if(a.type==AccountType.OBLIGATION){val protected=balance.coerceAtLeast(0)*reserve/100;(balance-protected).coerceAtLeast(0).coerceAtMost(a.remaining)}else a.remaining;val p=(suggested*100/a.remaining.coerceAtLeast(1)).toInt();Card(onClick=onOpen){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(a.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(due(a.dueDate),style=MaterialTheme.typography.labelMedium)};Text(tr(tr("المتبقي: ${money(a.remaining)}","Remaining: ${money(a.remaining)}"),"Remaining: ${money(a.remaining)}"));LinearProgressIndicator({(a.settledAmount.toFloat()/a.originalAmount.coerceAtLeast(1)).coerceIn(0f,1f)},Modifier.fillMaxWidth());Text(tr("اقتراح: ${money(suggested)} • $p%","Suggestion: ${money(suggested)} • $p%"));Button(onOpen,Modifier.fillMaxWidth()){Text(if(a.type==AccountType.OBLIGATION)tr("تسجيل سداد","Record payment") else tr("تسجيل استلام","Record receipt"))}}}}
@Composable private fun TxRow(t:FinanceTransaction,onUndo:()->Unit){ListItem(headlineContent={Text(t.description)},supportingContent={Text("${money(t.amount)} • ${date(t.createdAt)}")},leadingContent={Icon(if(t.type==TransactionType.PAYMENT||t.type==TransactionType.EXPENSE)Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,null)},trailingContent={IconButton(onUndo){Icon(Icons.Default.Undo,"تراجع")}})}
@Composable private fun EmptyState(t:String){Card{Box(Modifier.fillMaxWidth().padding(28.dp),contentAlignment=Alignment.Center){Text(t)}}}
@Composable private fun AmountField(v:String,on:(String)->Unit)=OutlinedTextField(v,on,label={Text(tr("المبلغ","Amount"))},modifier=Modifier.fillMaxWidth(),singleLine=true)
@Composable private fun IncomeDialog(dismiss:()->Unit,save:(Long,String)->Unit){var a by remember{mutableStateOf("")};var d by remember{mutableStateOf("دخل")};AlertDialog(onDismissRequest=dismiss,title={Text(tr("إضافة دخل","Add income"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){AmountField(a){a=it.filter(Char::isDigit)};OutlinedTextField(d,{d=it},label={Text(tr("الوصف","Description"))},modifier=Modifier.fillMaxWidth())}},confirmButton={TextButton(enabled=(a.toLongOrNull()?:0)>0,onClick={save(a.toLong(),d)}){Text(tr("إضافة","Add"))}},dismissButton={TextButton(dismiss){Text(tr("إلغاء","Cancel"))}})}
@Composable private fun AccountDialog(type:AccountType,dismiss:()->Unit,save:(String,Long,String,Long?)->Unit){var n by remember{mutableStateOf("")};var a by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};var dueText by remember{mutableStateOf("")};AlertDialog(onDismissRequest=dismiss,title={Text(if(type==AccountType.OBLIGATION)tr("إضافة التزام","Add obligation") else tr("إضافة مستحق","Add receivable"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(n,{n=it},label={Text(tr("اسم الشخص","Person name"))},modifier=Modifier.fillMaxWidth());AmountField(a){a=it.filter(Char::isDigit)};OutlinedTextField(dueText,{dueText=it.filter(Char::isDigit).take(8)},label={Text(tr("موعد الاستحقاق DDMMYYYY (اختياري)","Due date DDMMYYYY (optional)"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(note,{note=it},label={Text(tr("ملاحظات","Notes"))},modifier=Modifier.fillMaxWidth())}},confirmButton={TextButton(enabled=n.isNotBlank()&&(a.toLongOrNull()?:0)>0,onClick={save(n,a.toLong(),note,parseDue(dueText))}){Text(tr("حفظ","Save"))}},dismissButton={TextButton(dismiss){Text(tr("إلغاء","Cancel"))}})}
private fun parseDue(s:String):Long?=runCatching{if(s.length!=8)null else SimpleDateFormat("ddMMyyyy",Locale.US).apply{isLenient=false}.parse(s)?.time}.getOrNull()
@Composable private fun SettleDialog(a:Account,suggested:Long,suggestedPercent:Int,balance:Long,dismiss:()->Unit,save:(Long)->Unit){var p by remember{mutableFloatStateOf(if(a.remaining>0)suggested*100f/a.remaining else 0f)};var amount by remember{mutableStateOf(suggested.toString())};val max=if(a.type==AccountType.OBLIGATION)minOf(a.remaining,balance.coerceAtLeast(0)) else a.remaining;AlertDialog(onDismissRequest=dismiss,title={Text(if(a.type==AccountType.OBLIGATION)tr("تسجيل سداد","Record payment") else tr("تسجيل استلام","Record receipt"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("${a.name} • المتبقي ${money(a.remaining)}",fontWeight=FontWeight.Bold);Text(tr("المقترح: ${money(suggested)} ($suggestedPercent%)","Suggested: ${money(suggested)} ($suggestedPercent%)"));Text(tr("النسبة: ${p.toInt()}%","Percentage: ${p.toInt()}%"));Slider(p,{p=it;amount=(a.remaining*it/100f).toLong().coerceAtMost(max).toString()},0f..100f);AmountField(amount){amount=it.filter(Char::isDigit);p=((amount.toLongOrNull()?:0)*100f/a.remaining.coerceAtLeast(1)).coerceIn(0f,100f)};Text(tr("بعد العملية سيصبح المتبقي: ${money((a.remaining-(amount.toLongOrNull()?:0)).coerceAtLeast(0))}","Remaining after transaction: ${money((a.remaining-(amount.toLongOrNull()?:0)).coerceAtLeast(0))}"))}},confirmButton={TextButton(enabled=(amount.toLongOrNull()?:0) in 1..max,onClick={save(amount.toLong())}){Text(tr("تأكيد","Confirm"))}},dismissButton={TextButton(dismiss){Text(tr("إلغاء","Cancel"))}})}
@Composable private fun AccountDetailsDialog(a: Account, settle:()->Unit, edit:()->Unit, delete:()->Unit, dismiss:()->Unit) {
    AlertDialog(onDismissRequest=dismiss, title={Text(a.name)}, text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(if(a.type==AccountType.OBLIGATION) "التزام مالي" else "مستحق مالي", style=MaterialTheme.typography.labelLarge)
        Text(tr("المبلغ الأصلي: ${money(a.originalAmount)}","Original amount: ${money(a.originalAmount)}"))
        Text(tr(if(a.type==AccountType.OBLIGATION) "تم سداده" else "تم استلامه", if(a.type==AccountType.OBLIGATION) "Paid" else "Received") + ": ${money(a.settledAmount)}")
        Text(tr(tr("المتبقي: ${money(a.remaining)}","Remaining: ${money(a.remaining)}"),"Remaining: ${money(a.remaining)}"), fontWeight=FontWeight.Bold)
        LinearProgressIndicator({(a.settledAmount.toFloat()/a.originalAmount.coerceAtLeast(1)).coerceIn(0f,1f)}, Modifier.fillMaxWidth())
        Text(tr("الاستحقاق: ${due(a.dueDate)}","Due: ${due(a.dueDate)}"))
        if(a.note.isNotBlank()) Text(tr("ملاحظات: ${a.note}","Notes: ${a.note}"))
    }}, confirmButton={Button(settle){Text(if(a.type==AccountType.OBLIGATION) tr("تسجيل سداد","Record payment") else tr("تسجيل استلام","Record receipt"))}}, dismissButton={Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){TextButton(edit){Text("تعديل")};TextButton(delete){Text(tr("حذف","Delete"),color=MaterialTheme.colorScheme.error)}}})
}

@Composable private fun EditAccountDialog(a: Account, dismiss:()->Unit, save:(String,Long,String,Long?)->Unit) {
    var n by remember(a.id){mutableStateOf(a.name)}; var amount by remember(a.id){mutableStateOf(a.originalAmount.toString())}; var note by remember(a.id){mutableStateOf(a.note)}; var dueText by remember(a.id){mutableStateOf(a.dueDate?.let{SimpleDateFormat("ddMMyyyy",Locale.US).format(Date(it))} ?: "")}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("تعديل ${a.name}","Edit ${a.name}"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(n,{n=it},label={Text(tr("اسم الشخص","Person name"))},modifier=Modifier.fillMaxWidth());AmountField(amount){amount=it.filter(Char::isDigit)};OutlinedTextField(dueText,{dueText=it.filter(Char::isDigit).take(8)},label={Text(tr("موعد الاستحقاق DDMMYYYY","Due date DDMMYYYY"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(note,{note=it},label={Text(tr("ملاحظات","Notes"))},modifier=Modifier.fillMaxWidth());if(a.settledAmount>0)Text(tr("لا يمكن جعل المبلغ الأصلي أقل من المدفوع بالفعل: ${money(a.settledAmount)}","Original amount cannot be lower than the amount already paid: ${money(a.settledAmount)}"),style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(enabled=n.isNotBlank()&&(amount.toLongOrNull()?:0)>=a.settledAmount&& (amount.toLongOrNull()?:0)>0,onClick={save(n,amount.toLong(),note,parseDue(dueText))}){Text(tr("حفظ","Save"))}},dismissButton={TextButton(dismiss){Text(tr("إلغاء","Cancel"))}})
}

@Composable private fun SettingsDialog(current:Int,dark:Boolean,language:String,lock:Boolean,hasPin:Boolean,biometricEnabled:Boolean,biometricAvailable:Boolean,dismiss:()->Unit,save:(Int,Boolean,String,Boolean,Boolean)->Unit,export:()->Unit,import:()->Unit,setPin:(String)->Unit){
    var v by remember{mutableFloatStateOf(current.toFloat())}; var d by remember{mutableStateOf(dark)}; var l by remember{mutableStateOf(language)}; var locked by remember{mutableStateOf(lock && hasPin)}; var bio by remember{mutableStateOf(biometricEnabled && biometricAvailable)}; var showPin by remember{mutableStateOf(false)}; var pin by remember{mutableStateOf("")}; var pin2 by remember{mutableStateOf("")}; var pinError by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=dismiss,title={Text(tr("الإعدادات","Settings"))},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(tr("احتياطي الرصيد: ${v.toInt()}%","Balance reserve: ${v.toInt()}%")); Slider(v,{v=it},0f..80f,steps=15)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(tr("الوضع الداكن","Dark mode"));Switch(d,{d=it})}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(tr("قفل التطبيق","App lock"));Switch(locked,{checked-> if(checked){if(hasPin) locked=true else showPin=true}else locked=false})}
        if(hasPin) TextButton({showPin=true}){Text(tr("تغيير رمز PIN","Change PIN"))}
        if (biometricAvailable && hasPin) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(tr("فتح بالبصمة","Biometric unlock"));Switch(bio,{bio=it})}
        Divider(); Button(export,Modifier.fillMaxWidth()){Text(tr("نسخة احتياطية مشفرة","Encrypted backup"))}; OutlinedButton(import,Modifier.fillMaxWidth()){Text(tr("استعادة نسخة احتياطية","Restore backup"))}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(tr("اللغة","Language"));Row{FilterChip(selected=l=="ar",onClick={l="ar"},label={Text("العربية")});Spacer(Modifier.width(6.dp));FilterChip(selected=l=="en",onClick={l="en"},label={Text("English")})}}
    }},confirmButton={TextButton({save(v.toInt(),d,l,locked,bio);dismiss()}){Text(tr("حفظ","Save"))}},dismissButton={TextButton(dismiss){Text(tr("إلغاء","Cancel"))}})
    if(showPin) AlertDialog(onDismissRequest={showPin=false},title={Text(tr("تعيين رمز PIN","Set PIN"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text(tr("PIN الجديد","New PIN"))},singleLine=true);OutlinedTextField(pin2,{pin2=it.filter(Char::isDigit).take(8)},label={Text(tr("تأكيد PIN","Confirm PIN"))},singleLine=true);if(pinError)Text(tr("يجب أن يتكون PIN من 4 إلى 8 أرقام متطابقة","PIN must be 4–8 matching digits"),color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton(enabled=pin.length in 4..8 && pin==pin2,onClick={setPin(pin);locked=true;showPin=false;pin="";pin2=""}){Text(tr("حفظ","Save"))}},dismissButton={TextButton({showPin=false}){Text(tr("إلغاء","Cancel"))}})
}
