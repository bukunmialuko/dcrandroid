package com.dcrandroid.fragments;


import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.dcrandroid.MainActivity;
import com.dcrandroid.activities.ReaderActivity;
import com.dcrandroid.R;
import com.dcrandroid.data.Account;
import com.dcrandroid.data.Constants;
import com.dcrandroid.util.DcrConstants;
import com.dcrandroid.util.DecredInputFilter;
import com.dcrandroid.util.PreferenceUtil;
import com.dcrandroid.util.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.math.MathContext;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import mobilewallet.UnsignedTransaction;

import static android.app.Activity.RESULT_OK;

/**
 * Created by Macsleven on 28/11/2017.
 */

public class SendFragment extends android.support.v4.app.Fragment implements AdapterView.OnItemSelectedListener{

    private EditText address, amount;
    private TextView totalAmountSending, estimateFee, estimateSize, error_label, exchangeRateLabel, exchangeCurrency;
    private Spinner accountSpinner;
    private static final int SCANNER_ACTIVITY_RESULT_CODE = 0;
    private List<String> categories;
    private List<Integer> accountNumbers = new ArrayList<>();
    private ArrayAdapter dataAdapter;
    private ProgressDialog pd;
    private PreferenceUtil util;
    private DcrConstants constants = DcrConstants.getInstance();
    private boolean isSendAll = false, currencyIsDCR = true, textChanged = false;
    private String addressError = "", amountError = "";
    private double exchangeRate = -1;
    private BigDecimal originalAmount;
    private Button convertBtn;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View vi = inflater.inflate(R.layout.content_send, container, false);

        address = vi.findViewById(R.id.send_dcr_add);
        amount = vi.findViewById(R.id.send_dcr_amount);
        totalAmountSending = vi.findViewById(R.id.send_dcr_total_amt_sndng);
        estimateSize = vi.findViewById(R.id.send_dcr_estimate_size);
        estimateFee = vi.findViewById(R.id.send_dcr_estimate_fee);
        accountSpinner = vi.findViewById(R.id.send_dropdown);
        error_label = vi.findViewById(R.id.send_error_label);
        exchangeRateLabel = vi.findViewById(R.id.send_dcr_exchange_rate);
        exchangeCurrency = vi.findViewById(R.id.send_dcr_exchange_currency);
        convertBtn = vi.findViewById(R.id.send_btn_convert);

        accountSpinner.setOnItemSelectedListener(this);

        vi.findViewById(R.id.send_dcr_scan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(getActivity(), ReaderActivity.class);
                startActivityForResult(intent, SCANNER_ACTIVITY_RESULT_CODE);
            }
        });

        vi.findViewById(R.id.send_btn_tx).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final String destAddress = address.getText().toString();

                if (destAddress.equals("")) {
                    addressError  = "Destination Address can not be empty";
                }else if(!constants.wallet.isAddressValid(destAddress)){
                    addressError = "Destination Address is not valid";
                }else if (amount.getText().toString().equals("")) {
                    amountError = "Amount cannot be empty";
                }

                if(addressError.length() > 0 || amountError.length() > 0){
                    displayError(false);
                    return;
                }

                if (validateAmount(true)) {
                    amountError = "Amount is not valid";
                    displayError(false);
                    return;
                }

                final long amt = getAmount();

                displayError(true);

                showInputPassPhraseDialog(destAddress, amt);
            }
        });

        amount.setFilters(new InputFilter[]{new DecredInputFilter()});
        amount.addTextChangedListener(amountWatcher);

        address.addTextChangedListener(addressWatcher);

        return vi;
    }

    private TextWatcher addressWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (s.toString().equals("")) {
                addressError = "Destination Address can not be empty";
                displayError(false);
            } else if (!constants.wallet.isAddressValid(s.toString())) {
                addressError = "Destination Address is not valid";
                displayError(false);
            }else{
                addressError = "";
                displayError(false);
                constructTransaction();
            }
        }
    };

    private TextWatcher amountWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            textChanged = true;
            constructTransaction();
        }
    };

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FragmentActivity activity = getActivity();
        if (activity == null) {
            System.out.println("Activity is null");
            return;
        }

        util = new PreferenceUtil(activity);
        activity.setTitle(getString(R.string.send));

        // Spinner Drop down elements
        categories = new ArrayList<>();

        if (getContext() == null) {
            System.out.println("Context is null");
            return;
        }
        dataAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, categories);
        dataAdapter.setDropDownViewResource(android.R.layout.simple_dropdown_item_1line);
        accountSpinner.setAdapter(dataAdapter);

        if(Integer.parseInt(util.get(Constants.CURRENCY_CONVERSION, "0")) != 0) {
            getActivity().findViewById(R.id.exchange_details).setVisibility(View.VISIBLE);
            convertBtn.setVisibility(View.VISIBLE);
            convertBtn.setEnabled(true);
            convertBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exchangeCurrency();
                }
            });
        }

        getActivity().findViewById(R.id.send_dcr_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isSendAll) {
                    isSendAll = false;
                    amount.setEnabled(true);
                    ((TextView) v).setTextColor(Color.parseColor("#000000"));
                    constructTransaction();
                } else {
                    isSendAll = true;
                    try {
                        if(currencyIsDCR) {
                            amount.setText(Utils.formatDecredWithoutComma(constants.wallet.spendableForAccount(accountNumbers.get(accountSpinner.getSelectedItemPosition()), util.getBoolean(Constants.SPEND_UNCONFIRMED_FUNDS) ? 0 : Constants.REQUIRED_CONFIRMATIONS)));
                        }else{
                            BigDecimal currentAmount = new BigDecimal(Utils.formatDecredWithoutComma(constants.wallet.spendableForAccount(accountNumbers.get(accountSpinner.getSelectedItemPosition()), util.getBoolean(Constants.SPEND_UNCONFIRMED_FUNDS) ? 0 : Constants.REQUIRED_CONFIRMATIONS)), new MathContext(7));
                            currentAmount = currentAmount.setScale(9, RoundingMode.HALF_UP);
                            BigDecimal exchangeDecimal = new BigDecimal(exchangeRate);
                            exchangeDecimal = exchangeDecimal.setScale(9, RoundingMode.HALF_UP);
                            BigDecimal convertedAmount = currentAmount.multiply(exchangeDecimal, MathContext.DECIMAL128);

                            DecimalFormat format = new DecimalFormat();
                            format.applyPattern("#.########");

                            amount.setText(format.format(convertedAmount.doubleValue()));
                        }
                        amount.setEnabled(false);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    ((TextView) v).setTextColor(Color.parseColor("#2970FF"));
                }
            }
        });

        prepareAccounts();
    }

    private void setInvalid(){
        estimateSize.setText(Constants.DASH);
        totalAmountSending.setText(Constants.DASH);
        estimateFee.setText(Constants.DASH);
    }

    private String getDestinationAddress(){
        String destAddress = address.getText().toString();
        if (destAddress.equals(Constants.EMPTY_STRING)){
            destAddress = util.get(Constants.RECENT_ADDRESS);
            if(destAddress.equals(Constants.EMPTY_STRING)){
                try {
                    destAddress = constants.wallet.addressForAccount(0);
                    util.set(Constants.RECENT_ADDRESS, destAddress);
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
        return destAddress;
    }

    private long getAmount(){
        long amt;
        if(currencyIsDCR){
            amt = Utils.decredToAtom(amount.getText().toString());
        }else{
            BigDecimal currentAmount;
            if (textChanged){
                currentAmount = new BigDecimal(amount.getText().toString());
                currentAmount = currentAmount.setScale(9, RoundingMode.HALF_UP);
            }else{
                currentAmount = originalAmount;
            }

            BigDecimal exchangeDecimal = new BigDecimal(exchangeRate);
            exchangeDecimal = exchangeDecimal.setScale(9, RoundingMode.HALF_UP);
            BigDecimal convertedAmount = currentAmount.divide(exchangeDecimal, MathContext.DECIMAL128);
            amt = Utils.decredToAtom(convertedAmount.doubleValue());
        }
        return amt;
    }

    private void constructTransaction(){
        displayError(true);

        if(!validateAmount(false)){
            setInvalid();
            return;
        }

        try {
            String destAddress = getDestinationAddress();
            long amt = getAmount();

            UnsignedTransaction transaction = constants.wallet.constructTransaction(destAddress, amt, accountNumbers.get(accountSpinner.getSelectedItemPosition()), util.getBoolean(Constants.SPEND_UNCONFIRMED_FUNDS) ? 0 : Constants.REQUIRED_CONFIRMATIONS, isSendAll);

            float estFee = (0.001F * transaction.getEstimatedSignedSize()) / 1000;
            estimateFee.setText(Utils.formatDecred(estFee).concat(" DCR"));

            estimateSize.setText(String.format(Locale.getDefault(),"%d bytes",transaction.getEstimatedSignedSize()));

            totalAmountSending.setText(Utils.calculateTotalAmount(amt, transaction.getEstimatedSignedSize(), isSendAll).concat(" DCR"));

        }catch (final Exception e){
            e.printStackTrace();
            error_label.setText(e.getMessage().substring(0, 1).toUpperCase() + e.getMessage().substring(1));
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        if (requestCode == SCANNER_ACTIVITY_RESULT_CODE) {
            if(resultCode== RESULT_OK) {
                try {
                    String returnString = intent.getStringExtra(Constants.ADDRESS);
                    System.out.println("Code: "+returnString);
                    if(returnString.startsWith("decred:"))
                        returnString = returnString.replace("decred:","");
                    if(returnString.length() < 25){
                        Toast.makeText(SendFragment.this.getContext(), R.string.wallet_add_too_short, Toast.LENGTH_SHORT).show();
                        return;
                    }else if(returnString.length() > 36){
                        Toast.makeText(SendFragment.this.getContext(), R.string.wallet_addr_too_long, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    //TODO: Make available for mainnet
                    if(returnString.startsWith("T")){
                        address.setText(returnString);
                    }else{
                        Toast.makeText(SendFragment.this.getContext(), R.string.invalid_address_prefix, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(getContext(), R.string.error_not_decred_address, Toast.LENGTH_LONG).show();
                    address.setText("");
                }
            }
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if(isSendAll){
            try {
                amount.setText(Utils.formatDecred(constants.wallet.spendableForAccount(accountNumbers.get(accountSpinner.getSelectedItemPosition()), 0)));
                amount.setEnabled(false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        constructTransaction();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) { }

    private void prepareAccounts(){
        new Thread(){
            public void run(){
                try{
                    final ArrayList<Account> accounts = Account.parse(constants.wallet.getAccounts(util.getBoolean(Constants.SPEND_UNCONFIRMED_FUNDS) ? 0 : Constants.REQUIRED_CONFIRMATIONS));
                    accountNumbers.clear();
                    categories.clear();
                    for(int i = 0; i < accounts.size(); i++){
                        if(accounts.get(i).getAccountName().trim().equalsIgnoreCase("imported")){
                            continue;
                        }
                        categories.add(i, accounts.get(i).getAccountName() + " " + Utils.formatDecred(accounts.get(i).getBalance().getSpendable()));
                        accountNumbers.add(accounts.get(i).getAccountNumber());
                    }
                    if(getActivity() == null){
                        System.out.println("Activity is null");
                        return;
                    }
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dataAdapter.notifyDataSetChanged();
                        }
                    });
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        }.start();
    }

    public void startTransaction(final String passphrase, final String destAddress,final long amt){
        pd = Utils.getProgressDialog(getContext(),false,false,"Processing...");
        pd.show();
        new Thread(){
            public void run(){
                try {
                    int accountNumber = accountNumbers.get(accountSpinner.getSelectedItemPosition());
                    int requiredConfs = util.getBoolean(Constants.SPEND_UNCONFIRMED_FUNDS) ? 0 : Constants.REQUIRED_CONFIRMATIONS;
                    byte[] serializedTx = constants.wallet.sendTransaction(passphrase.getBytes(), destAddress, amt, accountNumber, requiredConfs, isSendAll);
                    List<Byte> hashList = new ArrayList<>();
                    for (byte aSerializedTx : serializedTx) {
                        hashList.add(aSerializedTx);
                    }
                    Collections.reverse(hashList);
                    final StringBuilder sb = new StringBuilder();
                    for(byte b : hashList){
                        sb.append(String.format(Locale.getDefault(),"%02x", b));
                    }
                    System.out.println("Hash: "+sb.toString());
                    if(getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if(pd.isShowing()){ pd.dismiss(); }
                                addressError = "";
                                showTxConfirmDialog(sb.toString());
                            }
                        });
                    }
                }catch (final Exception e){
                    e.printStackTrace();
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if(pd.isShowing()){
                                    pd.dismiss();
                                }
                                error_label.setText(e.getMessage().substring(0, 1).toUpperCase() + e.getMessage().substring(1));
                            }
                        });
                    }
                }
            }
        }.start();
    }

    public void showInputPassPhraseDialog(final String destAddress, final long amt) {
        final AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getContext());
        LayoutInflater inflater = getActivity().getLayoutInflater();
        final View dialogView = inflater.inflate(R.layout.input_passphrase_box, null);
        dialogBuilder.setCancelable(false);
        dialogBuilder.setView(dialogView);

        final EditText passphrase = dialogView.findViewById(R.id.passphrase_input);

        dialogBuilder.setMessage(String.format(Locale.getDefault(),"%s %s DCR", getString(R.string.transaction_confirmation), Utils.formatDecred(amt)));

        dialogBuilder.setPositiveButton(R.string.done, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                String pass = passphrase.getText().toString();
                if(pass.length() > 0){
                    startTransaction(pass, destAddress, amt);
                }
            }
        });

        dialogBuilder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                dialogBuilder.setCancelable(true);
            }
        });

        AlertDialog b = dialogBuilder.create();
        b.show();
        b.getButton(b.BUTTON_POSITIVE).setTextColor(Color.BLUE);
    }

    public void showTxConfirmDialog(final String txHash) {
        if(getActivity() == null){
            System.out.println("Activity is null");
            return;
        }
        if(getContext() == null){
            System.out.println("Context is null");
            return;
        }
        final AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getContext());
        LayoutInflater inflater = getActivity().getLayoutInflater();
        final View dialogView = inflater.inflate(R.layout.tx_confrimation_display, null);
        dialogBuilder.setCancelable(false);
        dialogBuilder.setView(dialogView);

        final TextView txHashtv = dialogView.findViewById(R.id.tx_hash_confirm_view);
        txHashtv.setText(txHash);
        txHashtv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(txHashtv.getText().toString());
            }
        });

        dialogBuilder.setTitle("Transaction was successful");
        dialogBuilder.setPositiveButton("OKAY", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                dialog.dismiss();
                displayOverview();
            }
        });

        dialogBuilder.setNeutralButton("VIEW ON DCRDATA", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                dialog.dismiss();
                String url = "https://testnet.dcrdata.org/tx/"+txHash;
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(browserIntent);
            }
        });

        AlertDialog b = dialogBuilder.create();
        b.show();
        b.getButton(DialogInterface.BUTTON_NEUTRAL).setTextColor(Color.BLUE);

        amount.setText(null);
        address.setText("");
        addressError = "";
        amountError = "";
        displayError(false);
    }

    public void copyToClipboard(String copyText) {
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk < android.os.Build.VERSION_CODES.HONEYCOMB) {
            android.text.ClipboardManager clipboard = (android.text.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard != null) {
                clipboard.setText(copyText);
            }
        } else {
            if(getContext() == null){
                System.out.println("Context is null");
                return;
            }
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData
                    .newPlainText(getString(R.string.your_address), copyText);
            if(clipboard != null)
                clipboard.setPrimaryClip(clip);
        }
        Toast toast = Toast.makeText(getContext(),
                R.string.tx_hash_copy, Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.END, 50, 50);
        toast.show();
    }

    private boolean validateAmount(boolean sending){
        String s = amount.getText().toString();
        if(s.length() == 0){
            if(sending) {
                amountError = "Amount is empty";
                displayError(false);
            }
            return false;
        }
        if(s.indexOf('.') != -1){
            String atoms = s.substring(s.indexOf('.'));
            if(atoms.length() > 9){
                amountError = "Amount has more then 8 decimal places";
                displayError(false);
                return false;
            }
        }
        if(Double.parseDouble(s) == 0){
            return false;
        }
        amountError = "";
        displayError(false);
        return true;
    }

    private void displayError(boolean empty) {
        if (empty){
            error_label.setText(null);
            return;
        }
        String error = addressError + "\n" + amountError;
        error_label.setText(error.trim());
    }

    private void exchangeCurrency(){
        exchangeRate = 40.96;
        if(exchangeRate == -1){
            new GetExchangeRate(Utils.getProgressDialog(getContext(), false, false, "Fetching Data"), this).execute();
            return;
        }

        String currency = getContext().getResources().getStringArray(R.array.currency_conversion_abbrv)[Integer.parseInt(util.get(Constants.CURRENCY_CONVERSION, "0"))];

        exchangeRateLabel.setText(String.format(Locale.getDefault(), "%.2f %s/DCR", exchangeRate, currency));
        exchangeCurrency.setText(getContext().getResources().getStringArray(R.array.currency_conversion)[Integer.parseInt(util.get(Constants.CURRENCY_CONVERSION, "0"))]);
        if(currencyIsDCR){
            //Using if dcr is true because it will be flipped later in the function
            convertBtn.setText(getContext().getResources().getStringArray(R.array.currency_conversion_abbrv)[Integer.parseInt(util.get(Constants.CURRENCY_CONVERSION, "0"))]);
        }else{
            convertBtn.setText("DCR");
        }
        if(amount.getText().toString().length() == 0){
            currencyIsDCR = !currencyIsDCR;
            return;
        }
        BigDecimal currentAmount;

        if(textChanged){
            System.out.println("Using edittext value");
            currentAmount = new BigDecimal(amount.getText().toString());
            currentAmount = currentAmount.setScale(9, RoundingMode.HALF_UP);
        }else{
            System.out.println("Using original Value");
            currentAmount = originalAmount;
        }

        BigDecimal exchangeDecimal = new BigDecimal(exchangeRate);
        exchangeDecimal = exchangeDecimal.setScale(9, RoundingMode.HALF_UP);
        DecimalFormat format = new DecimalFormat();
        format.applyPattern("#.########");
        System.out.println("Current Amount: "+currentAmount.doubleValue());
        if(currencyIsDCR){
            BigDecimal convertedAmount = currentAmount.multiply(exchangeDecimal);
            currencyIsDCR = !currencyIsDCR;
            textChanged = false;
            originalAmount = convertedAmount;
            System.out.println("Multiplied: "+ convertedAmount.doubleValue()+", Reverse: "+ convertedAmount.divide(exchangeDecimal, MathContext.DECIMAL128).doubleValue());
            amount.removeTextChangedListener(amountWatcher);
            amount.setText(format.format(convertedAmount.doubleValue()));
            amount.addTextChangedListener(amountWatcher);
        }else{
            BigDecimal convertedAmount = currentAmount.divide(exchangeDecimal, MathContext.DECIMAL128);
            currencyIsDCR = !currencyIsDCR;
            textChanged = false;
            originalAmount = convertedAmount;
            System.out.println("Multiplied: "+ convertedAmount.doubleValue()+", Reverse: "+ convertedAmount.multiply(exchangeDecimal).doubleValue());
            amount.removeTextChangedListener(amountWatcher);
            amount.setText(format.format(convertedAmount.doubleValue()));
            amount.addTextChangedListener(amountWatcher);
        }
    }

    private static class GetExchangeRate extends AsyncTask<Void, String, String>{

        private ProgressDialog pd;
        private SendFragment sendFragment;
        public GetExchangeRate(ProgressDialog pd, SendFragment sendFragment){
            this.pd = pd;
            this.sendFragment =  sendFragment;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pd.show();
        }

        @Override
        protected String doInBackground(Void... voids) {
            try {
                URL url = new URL(pd.getContext().getString(R.string.dcr_to_usd_exchange_url));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.setReadTimeout(7000);
                connection.setConnectTimeout(7000);
                connection.setRequestProperty("user-agent",sendFragment.util.get("user_agent",""));
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String line;
                StringBuilder result = new StringBuilder();
                while((line = br.readLine()) != null){
                    result.append(line);
                }
                br.close();
                connection.disconnect();
                return result.toString();
            } catch (MalformedURLException e) {
                e.printStackTrace();
                publishProgress(e.getMessage());
            } catch (IOException e) {
                e.printStackTrace();
                publishProgress(e.getMessage());
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            Toast.makeText(pd.getContext(), values[0], Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onPostExecute(String s) {
            super.onPostExecute(s);
            if(pd.isShowing()){
                pd.dismiss();
            }
            if(s == null){
                return;
            }
            try {
                JSONObject apiResult = new JSONObject(s);
                if(apiResult.getBoolean("success")){
                    JSONObject result = apiResult.getJSONObject("result");
                    sendFragment.exchangeRate = result.getDouble("Last");
                    sendFragment.exchangeCurrency();
                }else{
                    Toast.makeText(pd.getContext(), "Exchange failed with error: "+apiResult.getString("message"), Toast.LENGTH_SHORT).show();
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public void displayOverview() {
        if (getActivity() != null && getActivity() instanceof MainActivity){
            MainActivity mainActivity = (MainActivity) getActivity();
            mainActivity.displayOverview();
        }
    }
}
